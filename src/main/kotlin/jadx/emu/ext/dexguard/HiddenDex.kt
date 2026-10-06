package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.ClassInfo
import jadx.plugins.emu.exec.DvmClass
import jadx.plugins.emu.exec.DvmCtorHandle
import jadx.plugins.emu.exec.DvmField
import jadx.plugins.emu.exec.DvmMethodHandle
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HookRegistry
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.runtime.DvmObject
import org.slf4j.Logger
import java.nio.ByteBuffer
import java.nio.file.Files

internal class HiddenDex(
    private val base: MethodSource,
    private val engine: EngineContext,
    private val hooks: HookRegistry,
    private val limits: ExecLimits,
    private val log: Logger,
    private val onLoad: (ByteArray, DexInputSource) -> Unit,
    private val app: AppInfo,
    private val vfs: VirtualFs,
) {
    private val loaders: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private val pathLists = java.util.IdentityHashMap<Any, DvmObject>()
    private val appLoader: Any by lazy { newLoader() }
    private val sources = LinkedHashMap<String, DexInputSource>()

    /** Exposed so DexStreams and NativeBridge see hidden classes too (not just the base dex). */
    val composite: MethodSource = Composite()
    private var hiddenVm: Vm? = null

    fun register() {
        // (1) reflective construction: new Constructor(<loader>).newInstance(new Object[]{ byte[]/ByteBuffer... })
        hooks.add(CTOR_NEW_INSTANCE) { call ->
            val h = call.receiver as? DvmCtorHandle ?: return@add
            if (h.owner !in LOADER_CLASSES) return@add
            val args = (call.args.getOrNull(0) as? Array<*>)?.toList() ?: emptyList()
            val dexes = dexBytesOf(args)
            if (dexes.isEmpty()) return@add
            if (dexes.count { load(it) } > 0 || dexes.any { key(it) in sources }) call.replace(newLoader())
        }
        // (2) DIRECT construction of in-memory loaders (no reflection). Capture only; let the VM build the object.
        // NEEDS-VERIFICATION: exact <init> dispatch through HookRegistry in jadx-emu. Wrong signatures simply never fire.
        for (sig in IN_MEMORY_CTORS) hooks.add(sig) { call ->
            for (dex in dexBytesOf(call.args)) load(dex)
        }
        // (3) file-path loaders: resolve the path(s) through the virtual FS, then the APK, then load.
        for (sig in FILE_PATH_CTORS) hooks.add(sig) { call ->
            (call.args.getOrNull(0) as? String)?.let { loadFromPathSpec(it) }
        }
        hooks.add(DEXFILE_INIT) { call -> (call.args.getOrNull(0) as? String)?.let { loadFromPathSpec(it) } }
        hooks.add(DEXFILE_LOADDEX) { call -> (call.args.getOrNull(0) as? String)?.let { loadFromPathSpec(it) } }

        hooks.add("Ljava/lang/ClassLoader;->loadClass(Ljava/lang/String;)Ljava/lang/Class;") { call ->
            if (call.receiver?.let { isLoader(it) } != true) return@add
            val name = call.args.getOrNull(0) as? String ?: return@add
            call.replace(classNamed(name))
        }
        hooks.add(HostShims.METHOD_INVOKE) { call ->
            val h = call.receiver as? DvmMethodHandle ?: return@add
            val hm = h.hostMethod
            if (hm != null) {
                val target = call.args.getOrNull(0)
                val args = (call.args.getOrNull(1) as? Array<*>)?.toList() ?: emptyList()
                when {
                    hm.declaringClass == Class::class.java && hm.name == "getClassLoader" && target is DvmClass -> call.replace(appLoader)
                    hm.name == "loadClass" && target != null && isLoader(target) ->
                        (args.getOrNull(0) as? String)?.let { call.replace(classNamed(it)) }
                }
                return@add
            }
            val symbol = h.symbol ?: return@add
            val target = call.args.getOrNull(0)?.takeUnless { it == 0 }
            val args = (call.args.getOrNull(1) as? Array<*>)?.toList() ?: emptyList()
            val m = resolve(symbol.declClass, symbol.name, symbol.argTypes) ?: return@add
            call.replace(vm().invoke(m, args, if (m.isStatic) null else target))
        }
        hooks.add(CTOR_NEW_INSTANCE) { call ->
            val h = call.receiver as? DvmCtorHandle ?: return@add
            if (!isHidden(h.owner)) return@add
            val args = (call.args.getOrNull(0) as? Array<*>)?.toList() ?: emptyList()
            val cands = composite.methodsByName(h.owner, "<init>")
            val init = cands.firstOrNull { it.ref.argTypes == h.params } ?: cands.firstOrNull { it.ref.argTypes.size == args.size }
            if (init == null) return@add
            val obj = DvmObject(h.owner)
            vm().invoke(init, args, obj)
            call.replace(obj)
        }
        for (getter in GETTERS) {
            hooks.add("Ljava/lang/reflect/Field;->$getter") { call ->
                val f = call.receiver as? DvmField ?: return@add
                if (f.ref.declClass == BASE_LOADER && f.ref.name == "pathList") {
                    val target = call.args.getOrNull(0)
                    if (target != null && target in loaders) call.replace(pathListOf(target))
                    return@add
                }
                if (!isHidden(f.ref.declClass)) return@add
                val meta = composite.classInfo(f.ref.declClass)?.fields?.firstOrNull { it.ref.name == f.ref.name } ?: return@add
                val v = if (meta.isStatic) {
                    val vm = vm()
                    vm.ensureClinit(f.ref.declClass)
                    vm.staticsOf(f.ref.declClass)[meta.ref.key]
                } else {
                    (call.args.getOrNull(0) as? DvmObject)?.fields?.get(meta.ref.key)
                }
                call.replace(v)
            }
        }
    }

    val loadedCount: Int get() = sources.size

    fun isHidden(desc: String): Boolean = sources.values.any { it.classInfo(desc) != null }

    /** Feed externally-recovered bytes (e.g. from CryptoCapture) through the same load+dump pipeline. */
    fun feedDecrypted(bytes: ByteArray) {
        for (dex in DexBytes.extract(bytes)) load(dex)
    }

    /** Run a method that lives in a hidden dex on the composite VM (used by the multi-stage fixpoint). */
    fun invokeHidden(method: DexMethod, args: List<Any?>): Boolean {
        val receiver = if (method.isStatic) null else DvmObject(method.declClass)
        return runCatching { vm().invoke(method, args, receiver); true }
            .getOrElse { log.warn("hidden {}#{} failed: {}", method.declClass, method.ref.name, it.toString()); false }
    }

    /** Invoke a method on the composite VM with an explicit receiver and return its value (for DecryptCapture). */
    fun invokeValue(method: DexMethod, args: List<Any?>, receiver: Any?): Any? =
        runCatching { vm().invoke(method, args, receiver) }.getOrNull()

    private fun isLoader(v: Any): Boolean = v in loaders || (v is DvmObject && extendsHost(v.type, ClassLoader::class.java))

    private fun extendsHost(type: String, host: Class<*>): Boolean {
        var cur: String? = type
        while (cur != null) {
            val info = composite.classInfo(cur) ?: return runCatching { host.isAssignableFrom(jadx.plugins.emu.exec.hostClass(cur)) }.getOrDefault(false)
            cur = info.superType
        }
        return false
    }

    private fun classNamed(name: String): DvmClass? {
        val desc = "L" + name.replace('.', '/') + ";"
        return if (composite.classInfo(desc) != null) DvmClass(desc) else null
    }

    private fun newLoader(): Any = java.net.URLClassLoader(emptyArray()).also { loaders += it }

    private fun pathListOf(loader: Any): DvmObject = pathLists.getOrPut(loader) {
        DvmObject(PATH_LIST).apply {
            fields["$PATH_LIST.dexElements"] = arrayOfNulls<Any?>(0)
            fields["$PATH_LIST.nativeLibraryPathElements"] = arrayOfNulls<Any?>(0)
            fields["$PATH_LIST.nativeLibraryDirectories"] = ArrayList<Any?>()
            fields["$PATH_LIST.systemNativeLibraryDirectories"] = ArrayList<Any?>()
            fields["$PATH_LIST.definingContext"] = loader
        }
    }

    private fun vm(): Vm = hiddenVm ?: EngineContext(composite, limits, engine.host, engine.android)
        .newVm(limits, hooks = hooks).also { hiddenVm = it }

    private fun resolve(owner: String, name: String, argTypes: List<String>): DexMethod? {
        if (!isHidden(owner)) return null
        var cur: String? = owner
        while (cur != null) {
            val cands = composite.methodsByName(cur, name)
            cands.firstOrNull { it.ref.argTypes == argTypes }?.let { return it }
            cur = composite.classInfo(cur)?.superType
        }
        Telemetry.unresolved("$owner->$name")
        return null
    }

    private fun load(bytes: ByteArray): Boolean {
        val k = key(bytes)
        if (k in sources) return false
        val tmp = Files.createTempFile("jadx-emu-hidden", ".dex")
        return try {
            Files.write(tmp, bytes)
            val src = DexInputSource.load(tmp.toFile())
            sources[k] = src
            hiddenVm = null
            log.info("loaded hidden dex, {} bytes, {} classDefs, {} methods", bytes.size, DexBytes.classDefCount(bytes), src.allMethods().size)
            Telemetry.count("dex.loaded")
            Telemetry.event("dex", "loaded ${bytes.size}B, ${DexBytes.classDefCount(bytes)} classDefs")
            onLoad(bytes, src)
            true
        } catch (e: Exception) {
            log.warn("cannot parse hidden dex ({} bytes): {}", bytes.size, e.toString())
            false
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Resolve a dalvik path spec (colon-separated) from the virtual FS or the APK, then load every DEX found. */
    private fun loadFromPathSpec(spec: String) {
        for (raw in spec.split(':')) {
            val path = raw.trim()
            if (path.isEmpty()) continue
            val bytes = vfs.read(path)
                ?: app.resourceBytes(path.substringAfterLast('!').trimStart('/'))
                ?: continue
            for (dex in DexBytes.extract(bytes)) load(dex)
        }
    }

    private fun dexBytesOf(args: List<Any?>): List<ByteArray> = args.flatMap { a ->
        when (a) {
            is ByteBuffer -> DexBytes.extract(bufferBytes(a))
            is ByteArray -> DexBytes.extract(a)
            is Array<*> -> a.flatMap {
                when (it) {
                    is ByteBuffer -> DexBytes.extract(bufferBytes(it))
                    is ByteArray -> DexBytes.extract(it)
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun bufferBytes(b: ByteBuffer): ByteArray {
        val d = b.duplicate()
        d.position(0)
        return ByteArray(d.limit()).also { d.get(it) }
    }

    private fun key(bytes: ByteArray): String = DexBytes.sha256(bytes)

    private inner class Composite : MethodSource {
        override fun method(classDesc: String, shortId: String): DexMethod? =
            sources.values.firstNotNullOfOrNull { it.method(classDesc, shortId) } ?: base.method(classDesc, shortId)

        override fun classInfo(classDesc: String): ClassInfo? =
            sources.values.firstNotNullOfOrNull { it.classInfo(classDesc) } ?: base.classInfo(classDesc)

        override fun methodsByName(classDesc: String, name: String): List<DexMethod> =
            sources.values.firstOrNull { it.classInfo(classDesc) != null }?.methodsByName(classDesc, name) ?: base.methodsByName(classDesc, name)

        override fun methodsOf(classDesc: String): List<DexMethod> =
            sources.values.firstOrNull { it.classInfo(classDesc) != null }?.methodsOf(classDesc) ?: base.methodsOf(classDesc)

        override fun allMethods(): List<DexMethod> = sources.values.flatMap { it.allMethods() } + base.allMethods()

        override fun isNative(classDesc: String, shortId: String): Boolean =
            sources.values.firstOrNull { it.classInfo(classDesc) != null }?.isNative(classDesc, shortId) ?: base.isNative(classDesc, shortId)
    }

    private companion object {
        const val BASE_LOADER = "Ldalvik/system/BaseDexClassLoader;"
        const val PATH_LIST = "Ldalvik/system/DexPathList;"
        const val CTOR_NEW_INSTANCE = "Ljava/lang/reflect/Constructor;->newInstance([Ljava/lang/Object;)Ljava/lang/Object;"
        const val DEXFILE_INIT = "Ldalvik/system/DexFile;-><init>(Ljava/lang/String;)V"
        const val DEXFILE_LOADDEX = "Ldalvik/system/DexFile;->loadDex(Ljava/lang/String;Ljava/lang/String;I)Ldalvik/system/DexFile;"
        val LOADER_CLASSES = setOf(
            "Ldalvik/system/InMemoryDexClassLoader;", "Ldalvik/system/DexClassLoader;",
            "Ldalvik/system/PathClassLoader;", "Ldalvik/system/BaseDexClassLoader;",
        )
        val IN_MEMORY_CTORS = listOf(
            "Ldalvik/system/InMemoryDexClassLoader;-><init>(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V",
            "Ldalvik/system/InMemoryDexClassLoader;-><init>([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V",
            "Ldalvik/system/InMemoryDexClassLoader;-><init>([Ljava/nio/ByteBuffer;Ljava/lang/String;Ljava/lang/ClassLoader;)V",
        )
        val FILE_PATH_CTORS = listOf(
            "Ldalvik/system/DexClassLoader;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V",
            "Ldalvik/system/PathClassLoader;-><init>(Ljava/lang/String;Ljava/lang/ClassLoader;)V",
            "Ldalvik/system/PathClassLoader;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V",
            "Ldalvik/system/BaseDexClassLoader;-><init>(Ljava/lang/String;Ljava/io/File;Ljava/lang/String;Ljava/lang/ClassLoader;)V",
        )
        val GETTERS = listOf(
            "get(Ljava/lang/Object;)Ljava/lang/Object;", "getInt(Ljava/lang/Object;)I", "getLong(Ljava/lang/Object;)J",
            "getBoolean(Ljava/lang/Object;)Z", "getShort(Ljava/lang/Object;)S", "getByte(Ljava/lang/Object;)B",
            "getChar(Ljava/lang/Object;)C", "getFloat(Ljava/lang/Object;)F", "getDouble(Ljava/lang/Object;)D",
        )
    }
}
