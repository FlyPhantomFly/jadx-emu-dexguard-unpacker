package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.MethodSource
import java.io.File
import java.util.zip.ZipFile

/**
 * Ranks classes that look like DexGuard runtime loaders by reading the raw DEX (class hierarchy +
 * which loader/crypto/reflection APIs each class's code *references*), with NO decompilation. This is
 * the universal, fast, quiet detector: it finds reflective loaders (a class that builds a loader via
 * getDeclaredConstructor + newInstance + ZipFile, with no loader superclass and nothing in its
 * signatures) that neither a signature-only scan nor a decompile-everything scan can surface without
 * either missing them or flooding the log. No target-specific names anywhere - pure API heuristics.
 *
 * Falls back to a signature-only pass over the emulator source if the raw dex can't be read.
 * `mode`: "dex" (default) | "signature".
 */
internal class CandidateScanner(
    private val inputFiles: List<File>,
    private val source: MethodSource,
    private val mode: String = "dex",
) {
    data class Hit(val cls: String, val score: Int, val reasons: List<String>)

    fun scan(limit: Int = 25): List<Hit> {
        val scores = HashMap<String, Int>()
        val reasons = HashMap<String, MutableSet<String>>()
        val add: (String, Int, String) -> Unit = { desc, pts, why ->
            scores[desc] = (scores[desc] ?: 0) + pts
            reasons.getOrPut(desc) { LinkedHashSet() }.add(why)
        }
        val dexes = if (mode == "signature") emptyList() else collectDexes()
        if (dexes.isNotEmpty()) dexes.forEach { runCatching { DexRef(it).score(add) } }
        else signaturePass(add)
        return scores.entries.sortedByDescending { it.value }.take(limit)
            .map { Hit(it.key, it.value, (reasons[it.key] ?: emptySet()).toList()) }
    }

    private fun collectDexes(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for (f in inputFiles) {
            if (!f.isFile) continue
            if (f.name.endsWith(".dex")) {
                runCatching { out.add(f.readBytes()) }
                continue
            }
            runCatching {
                ZipFile(f).use { zip ->
                    zip.entries().asSequence()
                        .filter { Regex("""classes\d*\.dex""").matches(it.name) }
                        .forEach { e -> zip.getInputStream(e).use { out.add(it.readBytes()) } }
                }
            }
        }
        return out
    }

    /** Fallback: signature-only ranking from the emulator source (no dex bytes available). */
    private fun signaturePass(add: (String, Int, String) -> Unit) {
        val byClass = runCatching { source.allMethods().groupBy { it.declClass } }.getOrDefault(emptyMap())
        for ((desc, methods) in byClass) {
            val superType = runCatching { source.classInfo(desc)?.superType }.getOrNull()
            if (superType != null && superType in LOADER_SUPERS) add(desc, 60, "super:${simple(superType)}")
            for (m in methods) {
                when (m.ref.name) {
                    "loadClass", "findClass" -> add(desc, 40, m.ref.name)
                    "defineClass" -> add(desc, 50, "defineClass")
                }
                for (t in m.ref.argTypes) when (t) {
                    "[B" -> add(desc, 6, "byte[] param")
                    "Ljava/nio/ByteBuffer;", "[Ljava/nio/ByteBuffer;" -> add(desc, 15, "ByteBuffer param")
                    "Ldalvik/system/DexFile;" -> add(desc, 20, "DexFile param")
                }
            }
        }
    }

    private fun simple(desc: String): String = desc.removePrefix("L").removeSuffix(";").substringAfterLast('/')

    /** Minimal read-only DEX parser: scores classes by referenced loader/crypto/reflection method_ids. */
    private inner class DexRef(private val d: ByteArray) {
        private fun u1(o: Int) = d[o].toInt() and 0xff
        private fun u2(o: Int) = u1(o) or (u1(o + 1) shl 8)
        private fun u4(o: Int) = u2(o) or (u2(o + 2) shl 16)
        private fun uleb(o0: Int): Pair<Int, Int> {
            var o = o0; var r = 0; var s = 0
            while (true) { val b = u1(o); o++; r = r or ((b and 0x7f) shl s); if (b < 0x80) break; s += 7 }
            return r to o
        }

        private val stringIdsOff = u4(0x3c)
        private val typeIdsOff = u4(0x44)
        private val methodIdsSize = u4(0x58)
        private val methodIdsOff = u4(0x5c)
        private val classDefsSize = u4(0x60)
        private val classDefsOff = u4(0x64)

        private fun strx(i: Int): String {
            val off = u4(stringIdsOff + i * 4); val (n, p) = uleb(off)
            return String(d, p, n, Charsets.UTF_8)
        }
        private fun typ(i: Int): String = strx(u4(typeIdsOff + i * 4))

        // method_id -> interesting tag, precomputed
        private val tagByMid: Map<Int, String> by lazy {
            val m = HashMap<Int, String>()
            for (i in 0 until methodIdsSize) {
                val o = methodIdsOff + i * 8
                val name = strx(u4(o + 4))
                val tag = NAME_PTS[name]?.let { name } ?: CLASS_PTS.keys.firstOrNull { it == typ(u2(o)) }?.let { typ(u2(o)) }
                if (tag != null) m[i] = tag
            }
            m
        }

        fun score(add: (String, Int, String) -> Unit) {
            if (d.size < 0x70 || d[0].toInt() != 'd'.code) return
            val mids = tagByMid
            for (c in 0 until classDefsSize) {
                val o = classDefsOff + c * 32
                val cname = typ(u4(o))
                val supIdx = u4(o + 8)
                val cdOff = u4(o + 24)
                if (supIdx != -1) {
                    val sup = typ(supIdx)
                    if (sup in LOADER_SUPERS) add(cname, 60, "super:${simple(sup)}")
                }
                if (cdOff == 0) continue
                var p = cdOff
                val sf = uleb(p).also { p = it.second }.first
                val inf = uleb(p).also { p = it.second }.first
                val dm = uleb(p).also { p = it.second }.first
                val vm = uleb(p).also { p = it.second }.first
                repeat(sf + inf) { p = uleb(p).second; p = uleb(p).second }
                for (listLen in intArrayOf(dm, vm)) {
                    repeat(listLen) {
                        p = uleb(p).second                       // method_idx_diff (unused)
                        p = uleb(p).second                       // access_flags
                        val codeOff = uleb(p).also { p = it.second }.first
                        if (codeOff != 0) scoreCode(codeOff, cname, mids, add)
                    }
                }
            }
        }

        private fun scoreCode(off: Int, cname: String, mids: Map<Int, String>, add: (String, Int, String) -> Unit) {
            val insnsSize = u4(off + 12)
            val base = off + 16
            var i = 0
            while (i < insnsSize) {
                val op = u1(base + i * 2)
                if (op == 0x00) {                                 // nop / switch & array payloads
                    when (u1(base + i * 2 + 1)) {
                        1 -> { i += u2(base + (i + 1) * 2) * 2 + 4; continue }
                        2 -> { i += u2(base + (i + 1) * 2) * 4 + 2; continue }
                        3 -> {
                            val ew = u2(base + (i + 1) * 2); val sz = u4(base + (i + 2) * 2)
                            i += (sz * ew + 1) / 2 + 4; continue
                        }
                        else -> { i += 1; continue }
                    }
                }
                if (op in 0x6e..0x72 || op in 0x74..0x78) {       // invoke-kind / invoke-range
                    val mid = u2(base + (i + 1) * 2)
                    mids[mid]?.let { tag -> add(cname, NAME_PTS[tag] ?: CLASS_PTS[tag] ?: 5, tag) }
                }
                i += W[op].coerceAtLeast(1)
            }
        }
    }

    private companion object {
        val LOADER_SUPERS = setOf(
            "Ldalvik/system/PathClassLoader;", "Ldalvik/system/BaseDexClassLoader;",
            "Ldalvik/system/DexClassLoader;", "Ldalvik/system/InMemoryDexClassLoader;",
            "Ljava/lang/ClassLoader;", "Ljava/net/URLClassLoader;",
        )
        // referenced method names -> points
        val NAME_PTS = mapOf(
            "newInstance" to 12, "getDeclaredConstructor" to 15, "getConstructor" to 15,
            "defineClass" to 50, "loadClass" to 8, "doFinal" to 10, "getInstance" to 5,
            "loadDex" to 30, "openDexFile" to 30,
        )
        // referenced declaring-class descriptors -> points
        val CLASS_PTS = mapOf(
            "Ldalvik/system/InMemoryDexClassLoader;" to 60, "Ldalvik/system/DexClassLoader;" to 40,
            "Ldalvik/system/DexFile;" to 30, "Ljavax/crypto/Cipher;" to 25,
            "Ljava/util/zip/ZipFile;" to 10, "Ljava/util/zip/ZipInputStream;" to 10,
        )
        // Dalvik instruction widths in 16-bit code units (ported from the validated prototype).
        val W: IntArray = IntArray(256) { 1 }.also { w ->
            mapOf(0x01 to 1, 0x02 to 2, 0x03 to 3, 0x04 to 1, 0x05 to 2, 0x06 to 3, 0x07 to 1,
                0x08 to 2, 0x09 to 3, 0x0a to 1, 0x0b to 1, 0x0c to 1, 0x0d to 1,
                0x12 to 1, 0x13 to 2, 0x14 to 3, 0x15 to 2, 0x16 to 2, 0x17 to 3, 0x18 to 5, 0x19 to 2,
                0x1a to 2, 0x1b to 3, 0x1c to 2, 0x1d to 1, 0x1e to 1, 0x1f to 2, 0x20 to 2, 0x21 to 1,
                0x22 to 2, 0x23 to 2, 0x24 to 3, 0x25 to 3, 0x26 to 3, 0x27 to 1, 0x28 to 1, 0x29 to 2,
                0x2a to 3, 0x2b to 3, 0x2c to 3, 0x2d to 2, 0x2e to 2, 0x2f to 2, 0x30 to 2, 0x31 to 2,
                0x32 to 2, 0x33 to 2, 0x34 to 2, 0x35 to 2, 0x36 to 2, 0x37 to 2, 0x38 to 2, 0x39 to 2,
                0x3a to 2, 0x3b to 2, 0x3c to 2, 0x3d to 2).forEach { (o, v) -> w[o] = v }
            for (o in 0x44..0x6d) w[o] = 2
            for (o in 0x6e..0x72) w[o] = 3
            for (o in 0x74..0x78) w[o] = 3
            for (o in 0x90..0xaf) w[o] = 2
            for (o in 0xb0..0xcf) w[o] = 1
            for (o in 0xd0..0xe2) w[o] = 2
        }
    }
}
