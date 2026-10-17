package jadx.emu.ext.dexguard.unidbg

import java.io.File

/**
 * Standalone unidbg smoke test - run one native method from a .so OUTSIDE jadx, for fast iteration.
 *
 *   ./gradlew run --args="<so> L<class>; <method> <(args)ret> [hexInput]"
 * e.g.
 *   ./gradlew run --args="/path/libfoo.so Lcom/foo/Bar; decrypt ([B)[B 0011223344"
 *
 * Set UNIDBG_VERBOSE=1 for unidbg's own tracing. Prints the return value (byte[] as hex, String as
 * text). Confirm unidbg loads your .so and the marshaling is right here before wiring it into jadx.
 */
fun main(argv: Array<String>) {
    if (argv.size < 4) {
        System.err.println("usage: <so> L<class>; <method> <(args)ret> [hexInput]")
        return
    }
    val so = File(argv[0])
    if (!so.isFile) { System.err.println("no such .so: $so"); return }
    val className = argv[1].removePrefix("L").removeSuffix(";")
    val method = argv[2]
    val sig = argv[3]
    val args: List<Any?> = argv.getOrNull(4)?.let { listOf(hexToBytes(it)) } ?: emptyList()

    val runner = UnidbgRunner(listOf(so), sdk = 23, verbose = System.getenv("UNIDBG_VERBOSE") == "1") { println("WARN: $it") }
    when (val r = runner.invoke(className, method, sig, args, receiver = null)) {
        is ByteArray -> println("result: byte[${r.size}] = ${bytesToHex(r)}")
        null -> println("result: null")
        else -> println("result: $r")
    }
}

private fun hexToBytes(s: String): ByteArray {
    val t = s.replace(" ", "").replace("\n", "")
    require(t.length % 2 == 0) { "hex input must have even length" }
    return ByteArray(t.length / 2) { ((t[it * 2].digitToInt(16) shl 4) or t[it * 2 + 1].digitToInt(16)).toByte() }
}
private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
