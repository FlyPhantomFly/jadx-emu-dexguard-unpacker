package jadx.emu.ext.dexguard.unidbg

import com.github.unidbg.AndroidEmulator
import com.github.unidbg.linux.android.AndroidEmulatorBuilder
import com.github.unidbg.linux.android.AndroidResolver
import com.github.unidbg.linux.android.dvm.DvmObject
import com.github.unidbg.linux.android.dvm.StringObject
import com.github.unidbg.linux.android.dvm.VM
import com.github.unidbg.linux.android.dvm.array.ByteArray as UnidbgByteArray
import java.io.File

/**
 * Builds a unidbg Android emulator, loads the target .so(s), and invokes a JNI method - shared by the
 * bridge and the standalone harness. NEEDS-VERIFICATION on the unidbg API for your version: the static
 * `callStaticJniMethod*` calls are confirmed; the instance `callJniMethod*` / `newObject` calls are
 * best-effort and may need adjusting. No JNI-callback support (Phase 2).
 */
internal class UnidbgRunner(
    private val soLibs: List<File>,   // primary first; remaining are dependencies
    private val sdk: Int = 23,
    private val verbose: Boolean = false,
    private val warn: (String) -> Unit = {},
) {
    private val emulator: AndroidEmulator by lazy {
        AndroidEmulatorBuilder.for64Bit().build().also { it.memory.setLibraryResolver(AndroidResolver(sdk)) }
    }
    private val vm: VM by lazy { emulator.createDalvikVM().apply { setVerbose(verbose) } }
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        for (i in soLibs.indices.reversed()) {   // dependencies first, primary (index 0) last
            runCatching { vm.loadLibrary(soLibs[i], true) }
                .onFailure { warn("loadLibrary ${soLibs[i].name} failed: $it") }
        }
    }

    fun invoke(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any? {
        ensureLoaded()
        val dvmClass = vm.resolveClass(className)
        val jniArgs = Sig.argTypes(signature)
            .mapIndexed { i, t -> toUnidbg(args.getOrNull(i), t) ?: error("unsupported arg type $t") }
            .toTypedArray()
        val ret = Sig.returnType(signature)
        val sig = methodName + signature   // unidbg wants "name(args)ret"; signature is already "(args)ret"
        // Instance natives: a placeholder receiver (sufficient for decryptors that don't use `this`).
        val self: DvmObject<*>? = if (receiver != null) runCatching { dvmClass.newObject(null) }.getOrNull() else null
        return when (ret.firstOrNull()) {
            'V' -> { if (self != null) self.callJniMethod(emulator, sig, *jniArgs) else dvmClass.callStaticJniMethod(emulator, sig, *jniArgs); null }
            'I', 'S', 'B', 'C' -> if (self != null) self.callJniMethodInt(emulator, sig, *jniArgs) else dvmClass.callStaticJniMethodInt(emulator, sig, *jniArgs)
            'J' -> if (self != null) self.callJniMethodLong(emulator, sig, *jniArgs) else dvmClass.callStaticJniMethodLong(emulator, sig, *jniArgs)
            'Z' -> if (self != null) self.callJniMethodBoolean(emulator, sig, *jniArgs) else dvmClass.callStaticJniMethodBoolean(emulator, sig, *jniArgs)
            'L', '[' -> {
                val o: DvmObject<*>? = if (self != null) self.callJniMethodObject(emulator, sig, *jniArgs)
                else dvmClass.callStaticJniMethodObject(emulator, sig, *jniArgs)
                o?.let { fromUnidbg(it, ret) }
            }
            else -> null
        }
    }

    private fun toUnidbg(v: Any?, type: String): Any? = when (type) {
        "[B" -> (v as? ByteArray)?.let { UnidbgByteArray(vm, it) }
        "Ljava/lang/String;" -> (v as? String)?.let { StringObject(vm, it) }
        "I", "S", "B", "C" -> (v as? Int) ?: (v as? Char)?.code ?: 0
        "J" -> (v as? Long) ?: 0L
        "Z" -> (v as? Boolean) ?: false
        "F" -> (v as? Float) ?: 0f
        "D" -> (v as? Double) ?: 0.0
        else -> v
    }

    private fun fromUnidbg(o: DvmObject<*>, retType: String): Any? = when (retType) {
        "[B" -> (o as? UnidbgByteArray)?.value
        "Ljava/lang/String;" -> o.value?.toString()
        else -> o.value
    }

}

/** Minimal JVM/JNI signature parser (local copy so the standalone harness needs no core jar at runtime). */
internal object Sig {
    fun argTypes(sig: String): List<String> {
        val open = sig.indexOf('('); val close = sig.indexOf(')')
        if (open < 0 || close < 0 || close < open) return emptyList()
        val s = sig.substring(open + 1, close)
        val out = ArrayList<String>(); var i = 0
        while (i < s.length) {
            val start = i
            while (i < s.length && s[i] == '[') i++
            if (i >= s.length) break
            if (s[i] == 'L') { val semi = s.indexOf(';', i); if (semi < 0) break; i = semi + 1 } else i++
            out.add(s.substring(start, i))
        }
        return out
    }
    fun returnType(sig: String): String {
        val close = sig.indexOf(')')
        return if (close < 0 || close + 1 > sig.length) "V" else sig.substring(close + 1)
    }
}
