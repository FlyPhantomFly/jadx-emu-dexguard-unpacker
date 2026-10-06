package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.AndroidStubs
import jadx.plugins.emu.exec.HookRegistry
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.UNKNOWN

/**
 * PackageManager / PackageInfo / Signature / installer stubs so DexGuard's anti-tamper (RASP) checks
 * see a consistent, "legitimately installed, correctly signed" app. Signature bytes come from the
 * APK's own v1 certificate when present (AppInfo.signingCerts).
 *
 * Coverage is the commonly-checked surface (signatures, versionName/Code, installer). Less-common
 * PackageInfo fields return UNKNOWN; extend FIELDS below if a target reads something not here.
 */
internal class PackageStubs(private val app: AppInfo, private val profile: DeviceProfile) {

    private val pm = DvmObject(PACKAGE_MANAGER)

    private val signatures: Array<Any?> by lazy {
        val certs = app.signingCerts
        if (certs.isEmpty()) {
            arrayOf<Any?>(signature(PLACEHOLDER_CERT))
        } else {
            certs.map { signature(it) as Any? }.toTypedArray()
        }
    }

    private val signingInfo: DvmObject by lazy {
        DvmObject(SIGNING_INFO).apply {
            fields["$SIGNING_INFO.hasMultipleSigners"] = signatures.size > 1
        }
    }

    private val packageInfo: DvmObject by lazy {
        DvmObject(PACKAGE_INFO).apply {
            fields["$PACKAGE_INFO.packageName"] = app.packageName
            fields["$PACKAGE_INFO.versionName"] = app.versionName
            fields["$PACKAGE_INFO.versionCode"] = app.versionCode.toInt()
            fields["$PACKAGE_INFO.longVersionCode"] = app.versionCode
            fields["$PACKAGE_INFO.signatures"] = signatures
            fields["$PACKAGE_INFO.signingInfo"] = signingInfo
            fields["$PACKAGE_INFO.firstInstallTime"] = 1_600_000_000_000L
            fields["$PACKAGE_INFO.lastUpdateTime"] = 1_600_000_000_000L
        }
    }

    fun register(stubs: AndroidStubs, hooks: HookRegistry) {
        for (cls in CONTEXT_CLASSES) {
            stubs.registerMethod(cls, "getPackageManager") { _, _ -> pm }
            stubs.registerMethod(cls, "getPackageName") { _, _ -> app.packageName ?: UNKNOWN }
        }
        stubs.registerMethod(PACKAGE_MANAGER, "getPackageInfo") { _, _ -> packageInfo }
        stubs.registerMethod(PACKAGE_MANAGER, "getPackageArchiveInfo") { _, _ -> packageInfo }
        stubs.registerMethod(PACKAGE_MANAGER, "getInstallerPackageName") { _, _ -> profile.installerPackage ?: UNKNOWN }
        stubs.registerMethod(PACKAGE_MANAGER, "getInstallSourceInfo") { _, _ -> installSourceInfo() }

        stubs.registerMethod(SIGNING_INFO, "getApkContentsSigners") { _, _ -> signatures }
        stubs.registerMethod(SIGNING_INFO, "getSigningCertificateHistory") { _, _ -> signatures }
        stubs.registerMethod(SIGNING_INFO, "hasMultipleSigners") { _, _ -> signatures.size > 1 }
        stubs.registerMethod(SIGNING_INFO, "hasPastSigningCertificates") { _, _ -> false }

        stubs.registerMethod(SIGNATURE, "toByteArray") { r, _ -> (r as? DvmObject)?.fields?.get("$SIGNATURE.data") ?: UNKNOWN }
        stubs.registerMethod(SIGNATURE, "toCharsString") { r, _ -> (r as? DvmObject)?.let { hex(it.fields["$SIGNATURE.data"] as? ByteArray) } ?: UNKNOWN }
        stubs.registerMethod(SIGNATURE, "hashCode") { r, _ -> (r as? DvmObject)?.let { (it.fields["$SIGNATURE.data"] as? ByteArray)?.contentHashCode() } ?: 0 }
        stubs.registerMethod(SIGNATURE, "equals") { r, a ->
            val x = (r as? DvmObject)?.fields?.get("$SIGNATURE.data") as? ByteArray
            val y = (a.getOrNull(0) as? DvmObject)?.fields?.get("$SIGNATURE.data") as? ByteArray
            x != null && y != null && x.contentEquals(y)
        }

        stubs.registerMethod(INSTALL_SOURCE_INFO, "getInstallingPackageName") { _, _ -> profile.installerPackage ?: UNKNOWN }
        stubs.registerMethod(INSTALL_SOURCE_INFO, "getInitiatingPackageName") { _, _ -> profile.installerPackage ?: UNKNOWN }
        stubs.registerMethod(INSTALL_SOURCE_INFO, "getOriginatingPackageName") { _, _ -> UNKNOWN }
    }

    private fun signature(data: ByteArray): DvmObject =
        DvmObject(SIGNATURE).apply { fields["$SIGNATURE.data"] = data }

    private fun installSourceInfo(): DvmObject = DvmObject(INSTALL_SOURCE_INFO).apply {
        fields["$INSTALL_SOURCE_INFO.installingPackageName"] = profile.installerPackage
        fields["$INSTALL_SOURCE_INFO.initiatingPackageName"] = profile.installerPackage
    }

    private fun hex(b: ByteArray?): Any = if (b == null) UNKNOWN else b.joinToString("") { "%02x".format(it) }

    private companion object {
        const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
        const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"
        const val SIGNATURE = "Landroid/content/pm/Signature;"
        const val SIGNING_INFO = "Landroid/content/pm/SigningInfo;"
        const val INSTALL_SOURCE_INFO = "Landroid/content/pm/InstallSourceInfo;"
        val CONTEXT_CLASSES = listOf(
            "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/app/Application;",
            "Landroid/app/Activity;", "Landroid/view/ContextThemeWrapper;",
        )
        // A dummy DER-ish blob used only when the APK has no readable v1 cert, so signatures[] is non-empty.
        val PLACEHOLDER_CERT = ByteArray(256) { (it * 31 + 7).toByte() }
    }
}
