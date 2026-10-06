package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.AndroidStubs
import jadx.plugins.emu.exec.HookRegistry
import jadx.plugins.emu.exec.runtime.DvmObject
import java.io.ByteArrayOutputStream

/**
 * In-memory capture of files the app writes at runtime, so a loader that drops a decrypted dex to disk
 * and then loads it by path (DexClassLoader / DexFile) can be followed - HiddenDex resolves those paths
 * through [read].
 *
 * Why it works the way it does: jadx-emu models a host class like java.io.FileOutputStream as a
 * `UninitHost` at `<init>` and replaces it with a REAL host object afterwards (Interpreter does
 * `frame.replace(recv, hostExec.construct(...))`). So the receiver is a different object across
 * init -> write -> close, and the real stream would write actual files on the host. We therefore do NOT
 * track the receiver: every FileOutputStream call is intercepted at the invoke site and the real call is
 * replaced (so no host file I/O happens), while the path and bytes are tracked in a single "current
 * stream" slot keyed by order of operations (open -> write* -> close), which is how a decrypt-and-drop
 * loader uses the stream. Interleaved concurrent streams in one run are not distinguished.
 */
internal class VirtualFs {

    private val files = HashMap<String, ByteArray>()
    private var curPath: String? = null
    private var curBuf: ByteArrayOutputStream? = null
    private var lastFilePath: String? = null   // bridges File("path") -> FileOutputStream(file)

    fun read(path: String): ByteArray? = files[path] ?: files[path.substringAfterLast('/')]

    fun put(path: String, bytes: ByteArray) { files[path] = bytes }

    fun register(stubs: AndroidStubs, hooks: HookRegistry) {
        // Context.openFileOutput("name", mode) -> a stream bound to <filesDir>/name. The returned value is
        // never used as a real object (writes are intercepted below), so returning null is fine.
        for (cls in CONTEXT_CLASSES) stubs.registerMethod(cls, "openFileOutput") { _, a ->
            (a.getOrNull(0) as? String)?.let { open("/data/data/app/files/$it") }
            DvmObject("Ljava/io/FileOutputStream;")   // non-null so `if (fos != null)` passes; writes are hooked
        }

        // Remember File("path") / File(parent,"child") so FileOutputStream(File) can resolve its path.
        hooks.add("Ljava/io/File;-><init>(Ljava/lang/String;)V") { call ->
            lastFilePath = call.args.getOrNull(0) as? String
        }
        hooks.add("Ljava/io/File;-><init>(Ljava/lang/String;Ljava/lang/String;)V") { call ->
            val parent = call.args.getOrNull(0) as? String
            val child = call.args.getOrNull(1) as? String
            lastFilePath = if (parent.isNullOrEmpty()) child else "$parent/$child"
        }

        // FileOutputStream constructors: open a capture slot and skip the real (host) construction.
        for (sig in listOf(
            "Ljava/io/FileOutputStream;-><init>(Ljava/lang/String;)V",
            "Ljava/io/FileOutputStream;-><init>(Ljava/lang/String;Z)V",
        )) hooks.add(sig) { call -> (call.args.getOrNull(0) as? String)?.let { open(it) }; call.replace(null) }
        for (sig in listOf(
            "Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V",
            "Ljava/io/FileOutputStream;-><init>(Ljava/io/File;Z)V",
        )) hooks.add(sig) { call -> open(lastFilePath ?: "/data/data/app/files/out.bin"); call.replace(null) }

        // write / flush / close: capture bytes, never touch the host filesystem.
        hooks.add("Ljava/io/FileOutputStream;->write([B)V") { call ->
            (call.args.getOrNull(0) as? ByteArray)?.let { curBuf?.write(it) }
            call.replace(null)
        }
        hooks.add("Ljava/io/FileOutputStream;->write([BII)V") { call ->
            val data = call.args.getOrNull(0) as? ByteArray
            if (data != null) {
                val off = call.args.getOrNull(1) as? Int ?: 0
                val len = call.args.getOrNull(2) as? Int ?: data.size
                runCatching { curBuf?.write(data, off, len) }
            }
            call.replace(null)
        }
        hooks.add("Ljava/io/FileOutputStream;->write(I)V") { call ->
            (call.args.getOrNull(0) as? Int)?.let { curBuf?.write(it) }
            call.replace(null)
        }
        hooks.add("Ljava/io/FileOutputStream;->flush()V") { call -> call.replace(null) }
        hooks.add("Ljava/io/FileOutputStream;->close()V") { call -> commit(); call.replace(null) }
    }

    private fun open(path: String) {
        commit()                    // flush any previous un-closed stream
        curPath = path
        curBuf = ByteArrayOutputStream()
    }

    private fun commit() {
        val p = curPath; val b = curBuf
        if (p != null && b != null) files[p] = b.toByteArray()
        curPath = null; curBuf = null
    }

    private companion object {
        val CONTEXT_CLASSES = listOf(
            "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/app/Application;",
            "Landroid/app/Activity;", "Landroid/view/ContextThemeWrapper;",
        )
    }
}
