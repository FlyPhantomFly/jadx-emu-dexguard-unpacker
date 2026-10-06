package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.AndroidStubs
import jadx.plugins.emu.exec.DvmMethodHandle
import jadx.plugins.emu.exec.HookRegistry
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.UNKNOWN

internal class AppContextStubs(private val app: AppInfo, private val profile: DeviceProfile) {

    private val context = DvmObject("Landroid/app/Application;")
    private val resources = DvmObject("Landroid/content/res/Resources;")
    private val appInfo by lazy {
        DvmObject("Landroid/content/pm/ApplicationInfo;").apply {
            fields["Landroid/content/pm/ApplicationInfo;.targetSdkVersion"] = profile.targetSdk
            fields["Landroid/content/pm/ApplicationInfo;.minSdkVersion"] = profile.minSdk
            fields["Landroid/content/pm/ApplicationInfo;.packageName"] = app.packageName
            // FLAG_DEBUGGABLE (0x2) cleared by default so RASP sees a non-debuggable app.
            fields["Landroid/content/pm/ApplicationInfo;.flags"] = if (profile.debuggable) 0x2 else 0
        }
    }

    fun register(stubs: AndroidStubs, hooks: HookRegistry) {
        hooks.add(HostShims.METHOD_INVOKE) { call ->
            val symbol = (call.receiver as? DvmMethodHandle)?.symbol ?: return@add
            if (symbol.name in APP_GETTERS[symbol.declClass].orEmpty()) call.replace(context)
        }
        for (cls in CONTEXT_CLASSES) {
            stubs.registerMethod(cls, "getApplicationContext") { _, _ -> context }
            stubs.registerMethod(cls, "getBaseContext") { _, _ -> context }
            stubs.registerMethod(cls, "getResources") { _, _ -> resources }
            stubs.registerMethod(cls, "getPackageName") { _, _ -> app.packageName ?: UNKNOWN }
            stubs.registerMethod(cls, "getApplicationInfo") { _, _ -> appInfo }
            stubs.registerMethod(cls, "getString") { _, a -> resourceString(a) }
            stubs.registerMethod(cls, "getText") { _, a -> resourceString(a) }
        }
        stubs.registerMethod("Landroid/content/res/Resources;", "getString") { _, a -> resourceString(a) }
        stubs.registerMethod("Landroid/content/res/Resources;", "getText") { _, a -> resourceString(a) }
    }

    private fun resourceString(a: List<Any?>): Any? {
        val id = a.getOrNull(0) as? Int ?: return UNKNOWN
        return app.string(id) ?: UNKNOWN
    }

    private companion object {
        val APP_GETTERS = mapOf(
            "Landroid/app/ActivityThread;" to setOf("currentApplication", "currentActivityThread"),
            "Landroid/app/AppGlobals;" to setOf("getInitialApplication"),
        )
        val CONTEXT_CLASSES = listOf(
            "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/app/Application;",
            "Landroid/app/Activity;", "Landroid/view/ContextThemeWrapper;",
        )
    }
}
