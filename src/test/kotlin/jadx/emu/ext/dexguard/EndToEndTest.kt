package jadx.emu.ext.dexguard

import jadx.emu.ext.dexguard.EmuTestDex.cls
import jadx.emu.ext.dexguard.EmuTestDex.staticMethod
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.Diagnostic
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HostBoundary
import jadx.plugins.emu.exec.NotHandled
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.UnknownVal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Runs this extension's hooks, stubs and capturers against REAL emulated Dalvik: smali is assembled to a dex and the
 * jadx-emu interpreter executes it. This is the part the handler-level tests cannot show - that the engine really
 * dispatches to what this extension registers, in the shapes the handlers expect.
 */
class EndToEndTest {

    private val nop = NOPLogger.NOP_LOGGER

    @AfterEach fun restoreTelemetry() { Telemetry.enabled = false; Telemetry.reset() }

    // --- fixtures ----------------------------------------------------------------------------------------------

    /** A Runnable that XOR-decrypts "jmoon" with its key (3) and publishes the result to a static, as a loader would. */
    private val task = """
        .class public LTask;
        .super Ljava/lang/Object;
        .implements Ljava/lang/Runnable;
        .field public final key:I
        .method public constructor <init>(I)V
            .registers 2
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            iput p1, p0, LTask;->key:I
            return-void
        .end method
        .method public run()V
            .registers 4
            const-string v0, "jmoon"
            iget v1, p0, LTask;->key:I
            invoke-static {v0, v1}, LT;->dec(Ljava/lang/String;I)Ljava/lang/String;
            move-result-object v0
            sput-object v0, LT;->secret:Ljava/lang/String;
            return-void
        .end method
    """.trimIndent()

    private val t = cls("LT;", """
        .field public static secret:Ljava/lang/String;
    """ + staticMethod("dec(Ljava/lang/String;I)Ljava/lang/String;", 6, """
            invoke-virtual {p0}, Ljava/lang/String;->toCharArray()[C
            move-result-object v0
            const/4 v1, 0
            :loop
            array-length v2, v0
            if-ge v1, v2, :done
            aget-char v2, v0, v1
            xor-int/2addr v2, p1
            int-to-char v2, v2
            aput-char v2, v0, v1
            add-int/lit8 v1, v1, 1
            goto :loop
            :done
            new-instance v1, Ljava/lang/String;
            invoke-direct {v1, v0}, Ljava/lang/String;-><init>([C)V
            return-object v1
        """) + staticMethod("decOnce()Ljava/lang/String;", 2, """
            const-string v0, "jmoon"
            const/4 v1, 3
            invoke-static {v0, v1}, LT;->dec(Ljava/lang/String;I)Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("viaExecutor()Ljava/lang/String;", 3, """
            invoke-static {}, Ljava/util/concurrent/Executors;->newSingleThreadExecutor()Ljava/util/concurrent/ExecutorService;
            move-result-object v0
            const/4 v1, 3
            new-instance v2, LTask;
            invoke-direct {v2, v1}, LTask;-><init>(I)V
            invoke-interface {v0, v2}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V
            sget-object v0, LT;->secret:Ljava/lang/String;
            return-object v0
        """) + staticMethod("viaThread()Ljava/lang/String;", 3, """
            const/4 v1, 3
            new-instance v2, LTask;
            invoke-direct {v2, v1}, LTask;-><init>(I)V
            new-instance v0, Ljava/lang/Thread;
            invoke-direct {v0, v2}, Ljava/lang/Thread;-><init>(Ljava/lang/Runnable;)V
            invoke-virtual {v0}, Ljava/lang/Thread;->start()V
            sget-object v0, LT;->secret:Ljava/lang/String;
            return-object v0
        """) + staticMethod("radio()Ljava/lang/String;", 1, """
            invoke-static {}, Landroid/os/Build;->getRadioVersion()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """))

    /** Reflective access to the framework, as RASP/anti-tamper code does it. */
    private val rf = cls("LRf;", staticMethod("debug()Ljava/lang/Object;", 4, """
            const-string v0, "android.os.Debug"
            invoke-static {v0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
            move-result-object v0
            const-string v1, "isDebuggerConnected"
            const/4 v2, 0
            new-array v2, v2, [Ljava/lang/Class;
            invoke-virtual {v0, v1, v2}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
            move-result-object v0
            const/4 v1, 0
            const/4 v2, 0
            new-array v2, v2, [Ljava/lang/Object;
            invoke-virtual {v0, v1, v2}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """) + staticMethod("model()Ljava/lang/Object;", 3, """
            const-string v0, "android.os.Build"
            invoke-static {v0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
            move-result-object v0
            const-string v1, "MODEL"
            invoke-virtual {v0, v1}, Ljava/lang/Class;->getField(Ljava/lang/String;)Ljava/lang/reflect/Field;
            move-result-object v0
            const/4 v1, 0
            invoke-virtual {v0, v1}, Ljava/lang/reflect/Field;->get(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """))

    private val source: DexInputSource = EmuTestDex.assemble(task, t, rf)

    private fun vm(hooks: Hooks, src: DexInputSource = source): Vm {
        val limits = ExecLimits()
        return EngineCompat.newVm(EngineContext(src, limits, HostBoundary(), hooks), limits, null, hooks, false)
    }

    private fun run(vm: Vm, name: String): Any? = vm.invoke(EmuTestDex.method(source, "LT;", name))

    private fun configWith(vararg kv: Pair<String, String>): ExtensionConfig {
        val d = Files.createTempDirectory("e2e-cfg")
        Files.write(d.resolve("dexguard-unpacker.properties"), kv.joinToString("\n") { "${it.first}=${it.second}" }.toByteArray())
        return ExtensionConfig.load(d, nop)
    }

    // --- off-thread dispatch (item 1) --------------------------------------------------------------------------

    @Test fun withoutTheSchedulerAnOffThreadDecryptRecoversNothing() {
        assertNull(run(vm(Hooks()), "viaExecutor()Ljava/lang/String;"))
    }

    @Test fun schedulerRunsAnExecutorTaskSoItsDecryptIsRecovered() {
        val hooks = Hooks()
        var fallbackUsed = false
        SyncScheduler(source) { _, _, _ -> fallbackUsed = true; null }.register(ExtHooks(hooks.scope()))
        assertEquals("inllm", run(vm(hooks), "viaExecutor()Ljava/lang/String;"))
        assertFalse(fallbackUsed, "the dispatching VM can see the class, so the body must run on it (shared statics)")
    }

    @Test fun schedulerRunsANewThreadStartedTask() {
        val hooks = Hooks()
        SyncScheduler(source) { _, _, _ -> null }.register(ExtHooks(hooks.scope()))
        assertEquals("inllm", run(vm(hooks), "viaThread()Ljava/lang/String;"))
    }

    // --- stubs (registerMethod) ---------------------------------------------------------------------------------

    @Test fun aRegisteredStubAnswersAFrameworkCall() {
        val hooks = Hooks()
        ExtHooks(hooks.scope()).registerMethod("Landroid/os/Build;", "getRadioVersion") { _, _ -> "9.9" }
        assertEquals("9.9", run(vm(hooks), "radio()Ljava/lang/String;"))
    }

    @Test fun aStubReturningNotHandledFallsThroughToUnknown() {
        val hooks = Hooks()
        ExtHooks(hooks.scope()).registerMethod("Landroid/os/Build;", "getRadioVersion") { _, _ -> NotHandled }
        assertInstanceOf(UnknownVal::class.java, run(vm(hooks), "radio()Ljava/lang/String;"))
    }

    // --- reflective framework access through HostShims ----------------------------------------------------------------

    private fun hostShims(hooks: Hooks): DeviceProfile {
        val profile = DeviceProfile()
        HostShims(AppInfo(JadxDecompiler(JadxArgs())), profile).register(ExtHooks(hooks.scope()))
        return profile
    }

    @Test fun reflectiveMethodInvokeOnAFrameworkMethodReachesItsStub() {
        val hooks = Hooks()
        hostShims(hooks)
        // Method.invoke(Debug.isDebuggerConnected) is answered by the stub (false): HostShims routes it through the VM's host executor
        assertEquals(false, vm(hooks).invoke(EmuTestDex.method(source, "LRf;", "debug()Ljava/lang/Object;")))
    }

    @Test fun reflectiveBuildFieldReadsTheDeviceProfile() {
        val hooks = Hooks()
        val profile = hostShims(hooks)
        profile.model = "ZX-81"   // distinct from the engine's own default, so only the profile hook can produce it
        assertEquals("ZX-81", vm(hooks).invoke(EmuTestDex.method(source, "LRf;", "model()Ljava/lang/Object;")))
    }

    // --- DecryptCapture on an emulated method (item 2) ----------------------------------------------------------

    @Test fun decryptCaptureRecordsAnEmulatedMethodsRealReturn() {
        val hooks = Hooks()
        val sig = "LT;->dec(Ljava/lang/String;I)Ljava/lang/String;"
        val dc = DecryptCapture(nop, configWith("captureDecrypt" to "true", "captureDecryptMethods" to sig))
        dc.register(ExtHooks(hooks.scope()))
        assertEquals("inllm", run(vm(hooks), "decOnce()Ljava/lang/String;"), "the app still receives the real result")
        val dir = Files.createTempDirectory("e2e-dec")
        dc.flush(dir)
        val json = String(Files.readAllBytes(dir.resolve("decrypt/decrypt-capture.json")))
        assertTrue(json.contains("LT;->dec(Ljava/lang/String;I)Ljava/lang/String;"), json)
        assertTrue(json.contains("\"outText\": \"inllm\""), json)
    }

    // --- CryptoCapture on a real javax.crypto decrypt (item 2) --------------------------------------------------

    @Test fun cryptoCaptureFeedsADexDecryptedByARealCipher() {
        val plain = ByteArray(0x70).also { byteArrayOf(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00).copyInto(it) }  // "dex\n035\0"
        val ct = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(16), "AES"), IvParameterSpec(ByteArray(16)))
            doFinal(plain)
        }
        val data = ct.joinToString("\n") { "            ${it.toInt()}t" }
        val src = EmuTestDex.assemble(cls("LCr;", staticMethod("dec()[B", 9, """
            const/16 v0, 16
            new-array v1, v0, [B
            new-array v2, v0, [B
            new-instance v3, Ljavax/crypto/spec/SecretKeySpec;
            const-string v4, "AES"
            invoke-direct {v3, v1, v4}, Ljavax/crypto/spec/SecretKeySpec;-><init>([BLjava/lang/String;)V
            new-instance v5, Ljavax/crypto/spec/IvParameterSpec;
            invoke-direct {v5, v2}, Ljavax/crypto/spec/IvParameterSpec;-><init>([B)V
            const-string v4, "AES/CBC/PKCS5Padding"
            invoke-static {v4}, Ljavax/crypto/Cipher;->getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;
            move-result-object v4
            const/4 v6, 2
            invoke-virtual {v4, v6, v3, v5}, Ljavax/crypto/Cipher;->init(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V
            const/16 v0, ${ct.size}
            new-array v7, v0, [B
            fill-array-data v7, :ct
            invoke-virtual {v4, v7}, Ljavax/crypto/Cipher;->doFinal([B)[B
            move-result-object v8
            return-object v8
            :ct
            .array-data 1
$data
            .end array-data
        """)))
        val hooks = Hooks()
        val fed = ArrayList<ByteArray>()
        CryptoCapture(nop, configWith("captureCrypto" to "true")) { fed.add(it) }.register(ExtHooks(hooks.scope()))
        val out = vm(hooks, src).invoke(EmuTestDex.method(src, "LCr;", "dec()[B"))
        assertArrayEquals(plain, out as ByteArray, "the emulated code still receives the real plaintext")
        assertEquals(1, fed.size, "the decrypted dex must be fed to the loader exactly once")
        assertArrayEquals(plain, fed[0])
    }

    // --- engine diagnostics (item 8) ----------------------------------------------------------------------------

    @Test fun engineDiagnosticsNameTheHostCallTheEngineRefused() {
        Telemetry.reset(); Telemetry.enabled = true
        assertNull(run(vm(Hooks()), "viaExecutor()Ljava/lang/String;"))
        assertTrue(EngineDiagnostics.count(Diagnostic.Kind.HOST_DENIED) >= 1)
        val top = EngineDiagnostics.top(Diagnostic.Kind.HOST_DENIED, 5).map { it.first }
        assertTrue("Ljava/util/concurrent/Executors;->newSingleThreadExecutor()Ljava/util/concurrent/ExecutorService;" in top, top.toString())

        val dir = Files.createTempDirectory("e2e-diag")
        Diagnosis.report(nop, dir, 0, false, 0)
        val text = String(Files.readAllBytes(dir.resolve("diagnosis.txt")))
        assertTrue(text.contains("refused") && text.contains("Executors;->newSingleThreadExecutor"), text)
    }

    @Test fun diagnosticsAreSilentWhenTelemetryIsOff() {
        Telemetry.enabled = false
        run(vm(Hooks()), "viaExecutor()Ljava/lang/String;")
        assertEquals(0L, EngineDiagnostics.count(Diagnostic.Kind.HOST_DENIED))
    }
}
