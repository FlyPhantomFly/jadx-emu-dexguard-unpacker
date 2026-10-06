package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.AndroidStubs
import jadx.plugins.emu.exec.StubHandler
import jadx.plugins.emu.exec.runtime.UNKNOWN
import jadx.plugins.emu.exec.runtime.UnknownVal

/**
 * Stubs for the pure framework functions DexGuard folds integer constants through.
 * All of the non-trivial arithmetic lives in [AospMath] (unit-tested, no jadx dependency);
 * this object only adapts argument lists to those functions. Any UNKNOWN argument short-circuits
 * to UNKNOWN so a constant that cannot be folded stays symbolic instead of being guessed.
 *
 * Time-returning stubs use [VirtualClock] so polling loops terminate.
 */
internal object OpaqueConstants {

    fun register(stubs: AndroidStubs): Int {
        var n = 0
        fun stub(cls: String, name: String, handler: StubHandler) {
            stubs.registerMethod(cls, name) { recv, args ->
                val r = if (args.any { it is UnknownVal }) UNKNOWN else handler.invoke(recv, args)
                Telemetry.stub(cls, name, r === UNKNOWN)
                r
            }
            n++
        }

        val view = "Landroid/view/View;"
        stub(view, "combineMeasuredStates") { _, a -> AospMath.combineMeasuredStates(int(a, 0), int(a, 1)) }
        stub(view, "resolveSize") { _, a -> AospMath.resolveSize(int(a, 0), int(a, 1)) }
        stub(view, "resolveSizeAndState") { _, a -> AospMath.resolveSizeAndState(int(a, 0), int(a, 1), int(a, 2)) }
        stub(view, "getDefaultSize") { _, a -> AospMath.getDefaultSize(int(a, 0), int(a, 1)) }

        val spec = "Landroid/view/View\$MeasureSpec;"
        stub(spec, "makeMeasureSpec") { _, a -> AospMath.makeMeasureSpec(int(a, 0), int(a, 1)) }
        stub(spec, "getMode") { _, a -> AospMath.measureSpecMode(int(a, 0)) }
        stub(spec, "getSize") { _, a -> AospMath.measureSpecSize(int(a, 0)) }

        val vc = "Landroid/view/ViewConfiguration;"
        val vcValues = mapOf(
            "getScrollBarSize" to 4, "getScrollBarFadeDuration" to 250, "getScrollDefaultDelay" to 300,
            "getFadingEdgeLength" to 12, "getPressedStateDuration" to 64, "getLongPressTimeout" to 400,
            "getKeyRepeatTimeout" to 400, "getKeyRepeatDelay" to 50, "getTapTimeout" to 100, "getJumpTapTimeout" to 500,
            "getDoubleTapTimeout" to 300, "getEdgeSlop" to 12, "getTouchSlop" to 8, "getWindowTouchSlop" to 16,
            "getMinimumFlingVelocity" to 50, "getMaximumFlingVelocity" to 8000, "getMaximumDrawingCacheSize" to 480 * 800 * 4,
        )
        for ((name, v) in vcValues) stub(vc, name) { _, _ -> v }
        stub(vc, "getZoomControlsTimeout") { _, _ -> 3000L }
        stub(vc, "getGlobalActionKeyTimeout") { _, _ -> 500L }

        val tu = "Landroid/text/TextUtils;"
        stub(tu, "isEmpty") { _, a -> val s = a.getOrNull(0); s == null || s.toString().isEmpty() }
        stub(tu, "indexOf") { _, a -> textIndexOf(a, last = false) }
        stub(tu, "lastIndexOf") { _, a -> textIndexOf(a, last = true) }
        stub(tu, "getOffsetBefore") { _, a -> val i = int(a, 1); if (i == 0) 0 else i - 1 }
        stub(tu, "getOffsetAfter") { _, a -> val s = str(a, 0) ?: return@stub UNKNOWN; val i = int(a, 1); if (i >= s.length) s.length else i + 1 }
        stub(tu, "getTrimmedLength") { _, a -> str(a, 0)?.trim()?.length ?: UNKNOWN }
        stub(tu, "getCapsMode") { _, a -> val s = str(a, 0) ?: return@stub UNKNOWN; if (s.isEmpty()) 0 else int(a, 2) and 0x1000 }
        stub(tu, "equals") { _, a -> a.getOrNull(0)?.toString() == a.getOrNull(1)?.toString() }

        val color = "Landroid/graphics/Color;"
        stub(color, "alpha") { _, a -> AospMath.alpha(int(a, 0)) }
        stub(color, "red") { _, a -> AospMath.red(int(a, 0)) }
        stub(color, "green") { _, a -> AospMath.green(int(a, 0)) }
        stub(color, "blue") { _, a -> AospMath.blue(int(a, 0)) }
        stub(color, "rgb") { _, a -> AospMath.rgb(int(a, 0), int(a, 1), int(a, 2)) }
        stub(color, "argb") { _, a -> AospMath.argb(int(a, 0), int(a, 1), int(a, 2), int(a, 3)) }

        val key = "Landroid/view/KeyEvent;"
        stub(key, "getDeadChar") { _, _ -> 0 }
        stub(key, "keyCodeFromString") { _, a -> str(a, 0)?.let { AospMath.keyCodeFromString(it) } ?: UNKNOWN }
        stub(key, "getModifierMetaStateMask") { _, _ -> AospMath.getModifierMetaStateMask() }
        stub(key, "normalizeMetaState") { _, a -> AospMath.normalizeMetaState(int(a, 0)) }

        stub("Landroid/view/Gravity;", "getAbsoluteGravity") { _, a -> AospMath.getAbsoluteGravity(int(a, 0), int(a, 1)) }

        val elv = "Landroid/widget/ExpandableListView;"
        stub(elv, "getPackedPositionForGroup") { _, a -> AospMath.packedForGroup(int(a, 0)) }
        stub(elv, "getPackedPositionForChild") { _, a -> AospMath.packedForChild(int(a, 0), int(a, 1)) }
        stub(elv, "getPackedPositionType") { _, a -> AospMath.packedType(long(a, 0)) }
        stub(elv, "getPackedPositionGroup") { _, a -> AospMath.packedGroup(long(a, 0)) }
        stub(elv, "getPackedPositionChild") { _, a -> AospMath.packedChild(long(a, 0)) }

        stub("Landroid/text/AndroidCharacter;", "getMirror") { _, a ->
            when (val c = int(a, 0).toChar()) {
                '(' -> ')'; ')' -> '('; '[' -> ']'; ']' -> '['; '{' -> '}'; '}' -> '{'; '<' -> '>'; '>' -> '<'
                else -> c
            }
        }

        stub("Landroid/telephony/cdma/CdmaCellLocation;", "convertQuartSecToDecDegrees") { _, a -> int(a, 0) / 14400.0 }

        val tv = "Landroid/util/TypedValue;"
        stub(tv, "complexToFloat") { _, a -> AospMath.complexToFloat(int(a, 0)) }
        stub(tv, "complexToFraction") { _, a -> AospMath.complexToFraction(int(a, 0), float(a, 1), float(a, 2)) }

        stub("Landroid/graphics/PointF;", "length") { _, a -> val x = float(a, 0); val y = float(a, 1); Math.sqrt((x * x + y * y).toDouble()).toFloat() }
        stub("Landroid/graphics/drawable/Drawable;", "resolveOpacity") { _, a -> AospMath.resolveOpacity(int(a, 0), int(a, 1)) }
        stub("Landroid/graphics/ImageFormat;", "getBitsPerPixel") { _, a ->
            when (int(a, 0)) { 4 -> 16; 16 -> 16; 17 -> 12; 20 -> 16; 0x32315659 -> 12; 0x23 -> 12; 0x2a -> 12; 0x20203859 -> 8; else -> -1 }
        }
        stub("Landroid/view/MotionEvent;", "axisFromString") { _, a -> str(a, 0)?.let { s -> AXES.indexOf(s.removePrefix("AXIS_")).takeIf { it >= 0 } ?: -1 } ?: UNKNOWN }

        val proc = "Landroid/os/Process;"
        stub(proc, "myPid") { _, _ -> 4242 }
        stub(proc, "myTid") { _, _ -> 4242 }
        stub(proc, "myUid") { _, _ -> 10042 }
        stub(proc, "getGidForName") { _, _ -> -1 }
        stub(proc, "getThreadPriority") { _, _ -> 0 }
        stub(proc, "getElapsedCpuTime") { _, _ -> VirtualClock.currentThreadTimeMillis() }

        val clock = "Landroid/os/SystemClock;"
        stub(clock, "uptimeMillis") { _, _ -> VirtualClock.uptimeMillis() }
        stub(clock, "elapsedRealtime") { _, _ -> VirtualClock.elapsedRealtime() }
        stub(clock, "elapsedRealtimeNanos") { _, _ -> VirtualClock.elapsedRealtimeNanos() }
        stub(clock, "currentThreadTimeMillis") { _, _ -> VirtualClock.currentThreadTimeMillis() }
        stub(clock, "sleep") { _, a -> VirtualClock.advance(long(a, 0)); null }

        val audio = "Landroid/media/AudioTrack;"
        stub(audio, "getMaxVolume") { _, _ -> 1.0f }
        stub(audio, "getMinVolume") { _, _ -> 0.0f }

        // java.lang.{Integer,Long,Character} bit-twiddling DexGuard folds constants through. These are
        // only consulted where the engine doesn't already intrinsify them; the results match the JDK.
        val integer = "Ljava/lang/Integer;"
        stub(integer, "bitCount") { _, a -> Integer.bitCount(int(a, 0)) }
        stub(integer, "highestOneBit") { _, a -> Integer.highestOneBit(int(a, 0)) }
        stub(integer, "lowestOneBit") { _, a -> Integer.lowestOneBit(int(a, 0)) }
        stub(integer, "numberOfLeadingZeros") { _, a -> Integer.numberOfLeadingZeros(int(a, 0)) }
        stub(integer, "numberOfTrailingZeros") { _, a -> Integer.numberOfTrailingZeros(int(a, 0)) }
        stub(integer, "reverse") { _, a -> Integer.reverse(int(a, 0)) }
        stub(integer, "reverseBytes") { _, a -> Integer.reverseBytes(int(a, 0)) }
        stub(integer, "rotateLeft") { _, a -> Integer.rotateLeft(int(a, 0), int(a, 1)) }
        stub(integer, "rotateRight") { _, a -> Integer.rotateRight(int(a, 0), int(a, 1)) }
        stub(integer, "signum") { _, a -> Integer.signum(int(a, 0)) }

        val lng = "Ljava/lang/Long;"
        stub(lng, "bitCount") { _, a -> java.lang.Long.bitCount(long(a, 0)) }
        stub(lng, "highestOneBit") { _, a -> java.lang.Long.highestOneBit(long(a, 0)) }
        stub(lng, "lowestOneBit") { _, a -> java.lang.Long.lowestOneBit(long(a, 0)) }
        stub(lng, "numberOfLeadingZeros") { _, a -> java.lang.Long.numberOfLeadingZeros(long(a, 0)) }
        stub(lng, "numberOfTrailingZeros") { _, a -> java.lang.Long.numberOfTrailingZeros(long(a, 0)) }
        stub(lng, "reverse") { _, a -> java.lang.Long.reverse(long(a, 0)) }
        stub(lng, "reverseBytes") { _, a -> java.lang.Long.reverseBytes(long(a, 0)) }
        stub(lng, "rotateLeft") { _, a -> java.lang.Long.rotateLeft(long(a, 0), int(a, 1)) }
        stub(lng, "rotateRight") { _, a -> java.lang.Long.rotateRight(long(a, 0), int(a, 1)) }
        stub(lng, "signum") { _, a -> java.lang.Long.signum(long(a, 0)) }

        val chr = "Ljava/lang/Character;"
        stub(chr, "digit") { _, a -> Character.digit(int(a, 0), int(a, 1)) }
        stub(chr, "getNumericValue") { _, a -> Character.getNumericValue(int(a, 0).toChar()) }
        stub(chr, "toLowerCase") { _, a -> Character.toLowerCase(int(a, 0)) }
        stub(chr, "toUpperCase") { _, a -> Character.toUpperCase(int(a, 0)) }
        stub(chr, "isDigit") { _, a -> Character.isDigit(int(a, 0)) }
        stub(chr, "isLetter") { _, a -> Character.isLetter(int(a, 0)) }

        return n
    }

    private val AXES = listOf(
        "X", "Y", "PRESSURE", "SIZE", "TOUCH_MAJOR", "TOUCH_MINOR", "TOOL_MAJOR", "TOOL_MINOR", "ORIENTATION",
        "VSCROLL", "HSCROLL", "Z", "RX", "RY", "RZ", "HAT_X", "HAT_Y", "LTRIGGER", "RTRIGGER", "THROTTLE", "RUDDER",
        "WHEEL", "GAS", "BRAKE", "DISTANCE", "TILT",
    )

    private fun textIndexOf(a: List<Any?>, last: Boolean): Any? {
        val s = str(a, 0) ?: return UNKNOWN
        return when (val needle = a.getOrNull(1)) {
            is Char, is Int -> {
                val c = if (needle is Char) needle else (needle as Int).toChar()
                when (a.size) {
                    2 -> if (last) s.lastIndexOf(c) else s.indexOf(c)
                    3 -> if (last) s.lastIndexOf(c, int(a, 2)) else s.indexOf(c, int(a, 2))
                    else -> {
                        val start = int(a, 2); val end = int(a, 3)
                        if (last) s.substring(0, minOf(end, s.length)).lastIndexOf(c, start) else s.substring(0, minOf(end, s.length)).indexOf(c, start)
                    }
                }
            }
            else -> {
                val sub = needle?.toString() ?: return UNKNOWN
                when (a.size) {
                    2 -> if (last) s.lastIndexOf(sub) else s.indexOf(sub)
                    3 -> if (last) s.lastIndexOf(sub, int(a, 2)) else s.indexOf(sub, int(a, 2))
                    else -> {
                        val start = int(a, 2); val end = int(a, 3)
                        if (last) s.substring(0, minOf(end, s.length)).lastIndexOf(sub, start) else s.substring(0, minOf(end, s.length)).indexOf(sub, start)
                    }
                }
            }
        }
    }

    private fun int(a: List<Any?>, i: Int): Int = when (val v = a.getOrNull(i)) {
        is Int -> v; is Long -> v.toInt(); is Char -> v.code; is Byte -> v.toInt(); is Short -> v.toInt(); is Boolean -> if (v) 1 else 0
        else -> 0
    }

    private fun long(a: List<Any?>, i: Int): Long = when (val v = a.getOrNull(i)) { is Long -> v; else -> int(a, i).toLong() }

    private fun float(a: List<Any?>, i: Int): Float = when (val v = a.getOrNull(i)) {
        is Float -> v; is Int -> Float.fromBits(v); is Double -> v.toFloat(); is Long -> v.toFloat(); else -> 0f
    }

    private fun str(a: List<Any?>, i: Int): String? = when (val v = a.getOrNull(i)) { is String -> v; is UnknownVal, null -> null; else -> v.toString() }
}
