package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.DvmClass
import jadx.plugins.emu.exec.DvmField
import jadx.plugins.emu.exec.DvmMethodHandle
import jadx.plugins.emu.exec.NotHandled
import jadx.plugins.emu.exec.runtime.DvmObject
import java.io.ByteArrayInputStream

internal class HostShims(private val app: AppInfo, private val profile: DeviceProfile = DeviceProfile()) {

    fun register(hooks: ExtHooks) {
        registerFrameworkMembers(hooks)
        registerArchiveAccess(hooks)
        registerRaspEnv(hooks)
        hooks.registerMethod("Ljava/lang/System;", "identityHashCode") { _, a -> System.identityHashCode(a.getOrNull(0)) }
        hooks.add("Ljava/lang/System;->getProperty(Ljava/lang/String;)Ljava/lang/String;") { call ->
            SYSTEM_PROPERTIES[call.args.getOrNull(0)]?.let { call.replace(it.replace("{pkg}", app.packageName ?: "app")) }
        }
    }

    /** Timing + anti-tamper surface: a monotonic wall clock, no debugger, a consistent Build, ANDROID_ID. */
    private fun registerRaspEnv(hooks: ExtHooks) {
        hooks.add("Ljava/lang/System;->currentTimeMillis()J") { call -> call.replace(VirtualClock.currentTimeMillis()) }
        hooks.add("Ljava/lang/System;->nanoTime()J") { call -> call.replace(VirtualClock.nanoTime()) }

        for (cls in listOf("Landroid/os/Debug;")) {
            hooks.registerMethod(cls, "isDebuggerConnected") { _, _ -> false }
            hooks.registerMethod(cls, "waitingForDebugger") { _, _ -> false }
        }
        hooks.registerMethod("Landroid/app/ActivityManager;", "isUserAMonkey") { _, _ -> false }
        hooks.registerMethod("Landroid/os/Debug;", "threadCpuTimeNanos") { _, _ -> VirtualClock.nanoTime() }

        // Settings.Secure.getString(cr, "android_id") and friends
        for (cls in listOf("Landroid/provider/Settings\$Secure;", "Landroid/provider/Settings\$System;", "Landroid/provider/Settings\$Global;")) {
            hooks.registerMethod(cls, "getString") { _, a ->
                when (a.getOrNull(1) as? String) { "android_id" -> profile.androidId; else -> null }
            }
        }

        // Build.* read reflectively (Build.class.getDeclaredField("MODEL").get(null)). Direct sget-object
        // of Build fields goes through jadx-emu's own framework model, not this hook.
        for (getter in FIELD_GETTERS) {
            hooks.add("Ljava/lang/reflect/Field;->$getter") { call ->
                val f = call.receiver as? DvmField ?: return@add
                buildFieldValue(f.ref.declClass, f.ref.name)?.let { call.replace(it) }
            }
        }
    }

    private fun buildFieldValue(declClass: String, name: String): Any? = when (declClass) {
        "Landroid/os/Build;" -> when (name) {
            "MODEL" -> profile.model; "MANUFACTURER" -> profile.manufacturer; "BRAND" -> profile.brand
            "DEVICE" -> profile.device; "PRODUCT" -> profile.product; "HARDWARE" -> profile.hardware
            "FINGERPRINT" -> profile.fingerprint; "TAGS" -> profile.tags; "TYPE" -> profile.type
            else -> null
        }
        "Landroid/os/Build\$VERSION;" -> when (name) {
            "SDK_INT" -> profile.sdkInt
            "RELEASE" -> when (profile.sdkInt) { 34 -> "14"; 33 -> "13"; 32 -> "12"; 31 -> "12"; 30 -> "11"; 29 -> "10"; 28 -> "9"; 27 -> "8.1"; 26 -> "8.0"; else -> profile.sdkInt.toString() }
            else -> null
        }
        else -> null
    }

    private fun registerFrameworkMembers(hooks: ExtHooks) {
        for (getter in FIELD_GETTERS) {
            hooks.add("Ljava/lang/reflect/Field;->$getter") { call ->
                val f = call.receiver as? DvmField ?: return@add
                if (!hooks.isFrameworkClass(f.ref.declClass)) return@add
                val v = call.vm.hooks.env.field(f.ref.declClass, f.ref.name)
                if (v !== NotHandled) call.replace(v)
            }
        }
        hooks.add(METHOD_INVOKE) { call ->
            val h = call.receiver as? DvmMethodHandle ?: return@add
            val target = call.args.getOrNull(0)?.takeUnless { it == 0 }
            val args = argList(call.args.getOrNull(1))
            if (h.hostMethod != null) return@add
            val symbol = h.symbol ?: return@add
            if (!hooks.isFrameworkClass(symbol.declClass)) return@add
            // hostExec consults the stubs first; a framework method without one throws StubNotImplemented -> no replace
            val r = runCatching { if (target == null) call.vm.hostExec.invokeStatic(symbol, args) else call.vm.hostExec.invokeInstance(symbol, target, args) }.getOrNull()
            if (r != null && r !== NotHandled) call.replace(r)
        }
    }

    private fun registerArchiveAccess(hooks: ExtHooks) {
        hooks.add(METHOD_INVOKE) { call ->
            val m = (call.receiver as? DvmMethodHandle)?.hostMethod ?: return@add
            if (m.declaringClass != Class::class.java || call.args.getOrNull(0) !is DvmClass) return@add
            val name = argList(call.args.getOrNull(1)).getOrNull(0)?.toString() ?: return@add
            when (m.name) {
                "getResource" -> call.replace(app.resourceUrl(name))
                "getResourceAsStream" -> call.replace(app.resourceBytes(name)?.let { ByteArrayInputStream(it) })
            }
        }
        for (ctor in listOf("(Ljava/lang/String;)V", "(Ljava/io/File;)V", "(Ljava/io/File;I)V", "(Ljava/lang/String;Ljava/nio/charset/Charset;)V")) {
            hooks.add("Ljava/util/zip/ZipFile;-><init>$ctor") { call -> call.replace(null) }
        }
        hooks.add("Ljava/util/zip/ZipFile;->getEntry(Ljava/lang/String;)Ljava/util/zip/ZipEntry;") { call ->
            val name = call.args.getOrNull(0) as? String ?: return@add
            val bytes = app.resourceBytes(name) ?: return@add call.replace(null)
            call.replace(DvmObject(ZIP_ENTRY).apply { fields[ZIP_ENTRY_NAME] = name; fields[ZIP_ENTRY_SIZE] = bytes.size.toLong() })
        }
        hooks.add("Ljava/util/zip/ZipFile;->getInputStream(Ljava/util/zip/ZipEntry;)Ljava/io/InputStream;") { call ->
            val entry = call.args.getOrNull(0) as? DvmObject ?: return@add
            val name = entry.fields[ZIP_ENTRY_NAME] as? String ?: return@add
            app.resourceBytes(name)?.let { call.replace(ByteArrayInputStream(it)) }
        }
        hooks.add("Ljava/util/zip/ZipFile;->close()V") { call -> call.replace(null) }
        hooks.registerMethod(ZIP_ENTRY, "getName") { r, _ -> (r as? DvmObject)?.fields?.get(ZIP_ENTRY_NAME) ?: NotHandled }
        hooks.registerMethod(ZIP_ENTRY, "getSize") { r, _ -> (r as? DvmObject)?.fields?.get(ZIP_ENTRY_SIZE) ?: NotHandled }
    }

    private fun argList(v: Any?): List<Any?> = (v as? Array<*>)?.toList() ?: emptyList()

    companion object {
        const val METHOD_INVOKE = "Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"

        private const val ZIP_ENTRY = "Ljava/util/zip/ZipEntry;"
        private const val ZIP_ENTRY_NAME = "$ZIP_ENTRY.name"
        private const val ZIP_ENTRY_SIZE = "$ZIP_ENTRY.size"

        private val FIELD_GETTERS = listOf(
            "get(Ljava/lang/Object;)Ljava/lang/Object;", "getInt(Ljava/lang/Object;)I", "getLong(Ljava/lang/Object;)J",
            "getBoolean(Ljava/lang/Object;)Z", "getShort(Ljava/lang/Object;)S", "getByte(Ljava/lang/Object;)B",
            "getChar(Ljava/lang/Object;)C", "getFloat(Ljava/lang/Object;)F", "getDouble(Ljava/lang/Object;)D",
        )
        private val SYSTEM_PROPERTIES = mapOf(
            "java.io.tmpdir" to "/data/data/{pkg}/cache",
            "java.vm.version" to "2.1.0",
            "os.arch" to "aarch64",
            "user.dir" to "/",
        )
    }
}
