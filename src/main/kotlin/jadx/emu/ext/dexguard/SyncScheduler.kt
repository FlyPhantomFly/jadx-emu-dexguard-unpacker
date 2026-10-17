package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.runtime.DvmObject

/**
 * Runs work the app dispatches to another thread / executor / handler INLINE on the emulator, so a
 * loader that decrypts-and-loads off the main thread is followed.
 *
 * Why this exists: jadx-emu's host policy (`emu-unsafe`) denies `java.lang.Thread`, every
 * `java.util.concurrent` executor, `Future.get`, `android.os.Handler`, etc. So `new Thread(r).start()`,
 * `executor.submit(task)` or `handler.post(r)` evaluate to a no-op UNKNOWN and the Runnable/Callable
 * body NEVER executes - which means any loader or crypto hook inside it never fires. That is a silent,
 * protection-agnostic miss. Rather than make threads "real" (non-deterministic, reaches the host), we
 * intercept the dispatch points and execute the submitted body synchronously, in the caller's run, so
 * the decrypt/load it performs is captured exactly as if it had run on its own thread.
 *
 * The body runs on the VM that dispatched it (its statics and time limit are shared with the dispatching code,
 * so a write the body makes is the value the dispatcher reads back; a VM with separate statics would lose it).
 * Classes only the hidden-dex composite can see are run through [fallback] instead, and methods resolve against
 * [source] (composite = hidden + base). The same hooks are active, so loader/crypto hooks fire during the inline body.
 *
 * Scope (v1.0.7): Thread (both `class X extends Thread { run() }` and `new Thread(runnable)`), Executor
 * / ExecutorService execute + submit (with a completed-Future stub so a following `.get()` returns the
 * value), and the Android main-thread post points (Handler.post*, Activity.runOnUiThread, View.post).
 * NOT yet taken over - see IMPLEMENTATION_PLAN.md item 1: FutureTask via its own constructor (the
 * denied ctor loses object identity across init->run, same problem VirtualFs documents) and the
 * AsyncTask doInBackground/onPostExecute lifecycle.
 *
 * Not thread-safe: runs are serialized on one worker thread, same as [VirtualFs].
 */
internal class SyncScheduler(
    private val source: MethodSource,
    private val fallback: (DexMethod, List<Any?>, Any?) -> Any?,
) {
    // Runnables captured at `new Thread(runnable)`, drained LIFO at the matching start() when the Thread
    // object itself is not a resolvable DvmObject: the engine turns a denied Thread ctor into an UNKNOWN,
    // losing its identity across init -> start, so we fall back to construction order (the common
    // `new Thread(r).start()` is construct-immediately-then-start, so LIFO matches).
    private val pendingThreadRunnables = ArrayDeque<DvmObject>()

    // Re-entry guard so a body that re-dispatches itself can't recurse forever (the VM step/depth limits
    // bound it anyway; this keeps traces clean and avoids double-running the same object).
    private val running = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    fun register(hooks: ExtHooks) {
        registerThreads(hooks)
        registerExecutors(hooks)
        registerMainThreadPosts(hooks)
    }

    // --- Thread ------------------------------------------------------------

    private fun registerThreads(hooks: ExtHooks) {
        // Runnable at arg 0.
        for (sig in listOf(
            "$THREAD-><init>(Ljava/lang/Runnable;)V",
            "$THREAD-><init>(Ljava/lang/Runnable;Ljava/lang/String;)V",
        )) hooks.add(sig) { call -> captureThreadRunnable(call.args.getOrNull(0)) }

        // ThreadGroup-first ctors put the Runnable at arg 1.
        for (sig in listOf(
            "$THREAD-><init>(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;)V",
            "$THREAD-><init>(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;Ljava/lang/String;)V",
            "$THREAD-><init>(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;Ljava/lang/String;J)V",
        )) hooks.add(sig) { call -> captureThreadRunnable(call.args.getOrNull(1)) }

        hooks.add("$THREAD->start()V") { call -> if (dispatchThreadStart(call.vm, call.receiver)) call.replace(null) }
        hooks.add("$THREAD->run()V") { call ->
            val recv = call.receiver
            if (recv is DvmObject && runRunnable(call.vm, recv)) call.replace(null)
        }
    }

    private fun captureThreadRunnable(arg: Any?) {
        (arg as? DvmObject)?.takeIf { hasRun(it) }?.let { pendingThreadRunnables.addLast(it) }
    }

    /** start(): prefer the receiver's own run() (subclassed Thread); else the most recent new Thread(r). */
    private fun dispatchThreadStart(vm: Vm, receiver: Any?): Boolean {
        if (receiver is DvmObject && runRunnable(vm, receiver)) return true
        val r = pendingThreadRunnables.removeLastOrNull() ?: return false
        return runRunnable(vm, r)
    }

    // --- Executor / ExecutorService / Future -------------------------------

    private fun registerExecutors(hooks: ExtHooks) {
        for (cls in EXECUTOR_CLASSES) {
            hooks.add("$cls->execute(Ljava/lang/Runnable;)V") { call ->
                if (runRunnable(call.vm, call.args.getOrNull(0))) call.replace(null)
            }
            hooks.add("$cls->submit(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;") { call ->
                if (runRunnable(call.vm, call.args.getOrNull(0))) call.replace(futureOf(null))
            }
            hooks.add("$cls->submit(Ljava/lang/Runnable;Ljava/lang/Object;)Ljava/util/concurrent/Future;") { call ->
                if (runRunnable(call.vm, call.args.getOrNull(0))) call.replace(futureOf(call.args.getOrNull(1)))
            }
            hooks.add("$cls->submit(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;") { call ->
                runCallable(call.vm, call.args.getOrNull(0))?.let { call.replace(futureOf(it.value)) }
            }
        }
        // Make a following `.get()` on our completed-Future stub return the computed value.
        for (cls in FUTURE_CLASSES) {
            hooks.add("$cls->get()Ljava/lang/Object;") { call -> completed(call.receiver)?.let { call.replace(it.value) } }
            hooks.add("$cls->get(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;") { call -> completed(call.receiver)?.let { call.replace(it.value) } }
        }
    }

    // --- Android main-thread posts -----------------------------------------

    private fun registerMainThreadPosts(hooks: ExtHooks) {
        for (sig in POST_RETURN_BOOLEAN) hooks.add(sig) { call ->
            if (runRunnable(call.vm, call.args.getOrNull(0))) call.replace(true)
        }
        for (sig in POST_RETURN_VOID) hooks.add(sig) { call ->
            if (runRunnable(call.vm, call.args.getOrNull(0))) call.replace(null)
        }
    }

    // --- execution helpers -------------------------------------------------

    /** Resolve and run `run()V` on [obj] (a Runnable/Thread DvmObject). Returns whether it was handled. */
    private fun runRunnable(vm: Vm, obj: Any?): Boolean {
        if (obj !is DvmObject) return false
        if (!running.add(obj)) return true                        // already executing: swallow, don't recurse
        try {
            if (resolve(source, obj.type, "run()V") == null) return false
            Telemetry.count("sched.run")
            Telemetry.event("sched", "run() ${obj.type}")
            execute(vm, obj, "run()V")
            return true
        } finally { running.remove(obj) }
    }

    /** Resolve and run `call()` on a Callable DvmObject. Returns the result boxed, or null if not run. */
    private fun runCallable(vm: Vm, obj: Any?): Boxed? {
        if (obj !is DvmObject) return null
        if (!running.add(obj)) return Boxed(null)                 // already executing: suppress, don't recurse
        try {
            if (resolve(source, obj.type, "call()Ljava/lang/Object;") == null) return null
            Telemetry.count("sched.call")
            Telemetry.event("sched", "call() ${obj.type}")
            return Boxed(execute(vm, obj, "call()Ljava/lang/Object;"))
        } finally { running.remove(obj) }
    }

    private fun hasRun(obj: DvmObject): Boolean = resolve(source, obj.type, "run()V") != null

    private fun resolve(src: MethodSource, type: String, shortId: String): DexMethod? {
        var cur: String? = type
        while (cur != null) {
            src.method(cur, shortId)?.let { return it }
            cur = src.classInfo(cur)?.superType
        }
        return null
    }

    /**
     * Run [shortId] on [obj]. On [vm] itself whenever it can see the class: the body then shares that VM's static
     * fields and its time limit, so what it writes (a decrypted string, a loaded dex handle) is what the dispatching
     * code reads back. A body the dispatching VM cannot see (a class loaded into the hidden-dex composite after
     * that VM was built) runs on the fallback VM, which can. Never throws: both paths return UNKNOWN on failure.
     */
    private fun execute(vm: Vm, obj: DvmObject, shortId: String): Any? {
        resolve(vm.source, obj.type, shortId)?.let { return vm.invoke(it, emptyList(), obj) }
        val m = resolve(source, obj.type, shortId) ?: return null
        return fallback(m, emptyList(), obj)
    }

    // --- completed-Future stub ---------------------------------------------

    private fun futureOf(value: Any?): DvmObject =
        DvmObject(FUTURE_STUB).apply { fields[FUTURE_VALUE] = value; fields[FUTURE_DONE] = true }

    /** A boxed value: distinguishes "produced a (possibly null) result" from "didn't run" (null box). */
    private class Boxed(val value: Any?)

    /** The stored value if [receiver] is one of our completed-Future stubs, else null. */
    private fun completed(receiver: Any?): Boxed? {
        val o = receiver as? DvmObject ?: return null
        if (o.type != FUTURE_STUB || !o.fields.containsKey(FUTURE_VALUE)) return null
        return Boxed(o.fields[FUTURE_VALUE])
    }

    private companion object {
        const val THREAD = "Ljava/lang/Thread;"

        // Our synthetic completed Future. Typed as FutureTask so an `instanceof Future/FutureTask` check
        // and the get() hooks both match; the __emu_ fields are private bookkeeping.
        const val FUTURE_STUB = "Ljava/util/concurrent/FutureTask;"
        const val FUTURE_VALUE = "$FUTURE_STUB.__emu_value"
        const val FUTURE_DONE = "$FUTURE_STUB.__emu_done"

        val EXECUTOR_CLASSES = listOf(
            "Ljava/util/concurrent/Executor;",
            "Ljava/util/concurrent/ExecutorService;",
            "Ljava/util/concurrent/AbstractExecutorService;",
            "Ljava/util/concurrent/ThreadPoolExecutor;",
            "Ljava/util/concurrent/ScheduledExecutorService;",
            "Ljava/util/concurrent/ScheduledThreadPoolExecutor;",
        )
        val FUTURE_CLASSES = listOf(
            "Ljava/util/concurrent/Future;",
            "Ljava/util/concurrent/FutureTask;",
        )
        val POST_RETURN_BOOLEAN = listOf(
            "Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z",
            "Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z",
            "Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;Ljava/lang/Object;J)Z",
            "Landroid/os/Handler;->postAtTime(Ljava/lang/Runnable;J)Z",
            "Landroid/os/Handler;->postAtTime(Ljava/lang/Runnable;Ljava/lang/Object;J)Z",
            "Landroid/view/View;->post(Ljava/lang/Runnable;)Z",
            "Landroid/view/View;->postDelayed(Ljava/lang/Runnable;J)Z",
        )
        val POST_RETURN_VOID = listOf(
            "Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V",
            "Landroid/view/View;->postOnAnimation(Ljava/lang/Runnable;)V",
        )
    }
}
