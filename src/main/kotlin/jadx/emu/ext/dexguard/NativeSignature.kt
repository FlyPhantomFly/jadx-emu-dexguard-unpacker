package jadx.emu.ext.dexguard

/**
 * Parses JVM/JNI method signatures of the form `(argDescriptors)returnDescriptor`, e.g.
 * `([BLjava/lang/String;)[B`. Used by native backends to marshal args and the return value by type.
 * Pure and unit-tested.
 */
object NativeSignature {

    /** Argument type descriptors, in order. `([BLjava/lang/String;)[B` -> ["[B", "Ljava/lang/String;"]. */
    fun argTypes(sig: String): List<String> {
        val open = sig.indexOf('(')
        val close = sig.indexOf(')')
        if (open < 0 || close < 0 || close < open) return emptyList()
        return tokenize(sig.substring(open + 1, close))
    }

    /** Return type descriptor. `(...)V` -> "V", `(...)[B` -> "[B", `(...)Ljava/lang/String;` -> that. */
    fun returnType(sig: String): String {
        val close = sig.indexOf(')')
        return if (close < 0 || close + 1 > sig.length) "V" else sig.substring(close + 1)
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val start = i
            while (i < s.length && s[i] == '[') i++            // array dimensions
            if (i >= s.length) break
            if (s[i] == 'L') {                                 // object: Lpkg/Name;
                val semi = s.indexOf(';', i)
                if (semi < 0) break
                i = semi + 1
            } else {
                i++                                            // primitive: one char
            }
            out.add(s.substring(start, i))
        }
        return out
    }
}
