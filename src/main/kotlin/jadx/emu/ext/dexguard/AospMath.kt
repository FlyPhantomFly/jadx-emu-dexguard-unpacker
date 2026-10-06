package jadx.emu.ext.dexguard

/**
 * Pure re-implementations of the AOSP framework functions DexGuard uses to hide integer
 * constants behind opaque predicates. Everything here is side-effect free and has NO jadx-emu
 * dependency, so it runs on a plain JVM and is unit-tested in AospMathTest.
 *
 * Values below are taken from AOSP (android.view.View, View.MeasureSpec, android.graphics.Color,
 * android.view.KeyEvent, android.widget.ExpandableListView, android.util.TypedValue,
 * android.graphics.drawable.Drawable, android.view.Gravity).
 */
internal object AospMath {

    // --- View.MeasureSpec ----------------------------------------------------
    const val MODE_SHIFT = 30
    const val MODE_MASK = 0x3 shl MODE_SHIFT                 // 0xC0000000
    const val UNSPECIFIED = 0 shl MODE_SHIFT                 // 0x00000000
    const val EXACTLY = 1 shl MODE_SHIFT                     // 0x40000000
    const val AT_MOST = 2 shl MODE_SHIFT                     // 0x80000000
    const val MEASURED_STATE_MASK = -0x1000000              // 0xFF000000
    const val MEASURED_SIZE_MASK = 0x00ffffff
    const val MEASURED_STATE_TOO_SMALL = 0x01000000

    fun measureSpecMode(spec: Int): Int = spec and MODE_MASK
    fun measureSpecSize(spec: Int): Int = spec and MODE_MASK.inv()
    fun makeMeasureSpec(size: Int, mode: Int): Int = (size and MODE_MASK.inv()) or (mode and MODE_MASK)
    fun combineMeasuredStates(a: Int, b: Int): Int = a or b

    fun resolveSizeAndState(size: Int, spec: Int, childState: Int): Int {
        val specSize = measureSpecSize(spec)
        val result = when (measureSpecMode(spec)) {
            AT_MOST -> if (specSize < size) specSize or MEASURED_STATE_TOO_SMALL else size
            EXACTLY -> specSize
            else -> size                                    // UNSPECIFIED
        }
        return result or (childState and MEASURED_STATE_MASK)
    }

    fun resolveSize(size: Int, spec: Int): Int = resolveSizeAndState(size, spec, 0) and MEASURED_SIZE_MASK

    // Previously treated AT_MOST like UNSPECIFIED (matched 0xC0000000 instead of 0x80000000).
    fun getDefaultSize(size: Int, spec: Int): Int = when (measureSpecMode(spec)) {
        AT_MOST, EXACTLY -> measureSpecSize(spec)
        else -> size                                        // UNSPECIFIED
    }

    // --- Color ---------------------------------------------------------------
    fun alpha(c: Int): Int = c ushr 24
    fun red(c: Int): Int = (c shr 16) and 0xff
    fun green(c: Int): Int = (c shr 8) and 0xff
    fun blue(c: Int): Int = c and 0xff
    fun rgb(r: Int, g: Int, b: Int): Int = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    // --- Drawable.resolveOpacity --------------------------------------------
    // PixelFormat: UNKNOWN=0, TRANSLUCENT=-3, TRANSPARENT=-2, OPAQUE=-1.
    // Previously ignored UNKNOWN, checked OPAQUE before TRANSPARENT, and fell back to TRANSLUCENT.
    fun resolveOpacity(a: Int, b: Int): Int = when {
        a == b -> a
        a == 0 || b == 0 -> 0            // UNKNOWN
        a == -3 || b == -3 -> -3         // TRANSLUCENT
        a == -2 || b == -2 -> -2         // TRANSPARENT
        else -> -1                       // OPAQUE
    }

    // --- KeyEvent meta-state -------------------------------------------------
    const val META_SHIFT_ON = 0x1
    const val META_ALT_ON = 0x2
    const val META_SYM_ON = 0x4
    const val META_FUNCTION_ON = 0x8
    const val META_ALT_LEFT_ON = 0x10
    const val META_ALT_RIGHT_ON = 0x20
    const val META_SHIFT_LEFT_ON = 0x40
    const val META_SHIFT_RIGHT_ON = 0x80
    const val META_CTRL_ON = 0x1000
    const val META_CTRL_LEFT_ON = 0x2000
    const val META_CTRL_RIGHT_ON = 0x4000
    const val META_META_ON = 0x10000
    const val META_META_LEFT_ON = 0x20000
    const val META_META_RIGHT_ON = 0x40000
    const val META_CAPS_LOCK_ON = 0x100000
    const val META_NUM_LOCK_ON = 0x200000
    const val META_SCROLL_LOCK_ON = 0x400000

    // Correct value is 0x770FF; the previous stub returned 0x33077.
    const val META_MODIFIER_MASK =
        META_SHIFT_ON or META_SHIFT_LEFT_ON or META_SHIFT_RIGHT_ON or
        META_ALT_ON or META_ALT_LEFT_ON or META_ALT_RIGHT_ON or
        META_CTRL_ON or META_CTRL_LEFT_ON or META_CTRL_RIGHT_ON or
        META_META_ON or META_META_LEFT_ON or META_META_RIGHT_ON or
        META_SYM_ON or META_FUNCTION_ON
    const val META_ALL_MASK =
        META_MODIFIER_MASK or META_CAPS_LOCK_ON or META_NUM_LOCK_ON or META_SCROLL_LOCK_ON

    fun getModifierMetaStateMask(): Int = META_MODIFIER_MASK

    // Faithful port (the old stub was `x and 0x33077.inv().inv()`, i.e. a no-op mask).
    fun normalizeMetaState(input: Int): Int {
        var m = input
        if (m and (META_SHIFT_LEFT_ON or META_SHIFT_RIGHT_ON) != 0) m = m or META_SHIFT_ON
        if (m and (META_ALT_LEFT_ON or META_ALT_RIGHT_ON) != 0) m = m or META_ALT_ON
        if (m and (META_CTRL_LEFT_ON or META_CTRL_RIGHT_ON) != 0) m = m or META_CTRL_ON
        if (m and (META_META_LEFT_ON or META_META_RIGHT_ON) != 0) m = m or META_META_ON
        return m and META_ALL_MASK
    }

    // --- ExpandableListView packed positions --------------------------------
    // PACKED_POSITION_VALUE_NULL is 0x00000000FFFFFFFF, NOT -1. Type bit is bit 63.
    const val PACKED_NULL = 0xffffffffL
    private const val PACKED_MASK_GROUP = 0x7fffffff00000000L
    private const val PACKED_MASK_CHILD = 0x00000000ffffffffL
    private const val PACKED_TYPE_BIT = 1L shl 63

    fun packedForGroup(group: Int): Long = (group.toLong() shl 32) and PACKED_MASK_GROUP
    fun packedForChild(group: Int, child: Int): Long =
        PACKED_TYPE_BIT or ((group.toLong() shl 32) and PACKED_MASK_GROUP) or (child.toLong() and PACKED_MASK_CHILD)
    fun packedType(p: Long): Int = if (p == PACKED_NULL) 2 else if (p and PACKED_TYPE_BIT != 0L) 1 else 0
    fun packedGroup(p: Long): Int = if (p == PACKED_NULL) -1 else ((p and PACKED_MASK_GROUP) shr 32).toInt()
    fun packedChild(p: Long): Int =
        if (p == PACKED_NULL || p and PACKED_TYPE_BIT == 0L) -1 else (p and PACKED_MASK_CHILD).toInt()

    // --- Gravity.getAbsoluteGravity -----------------------------------------
    const val GRAV_LEFT = 0x3
    const val GRAV_RIGHT = 0x5
    const val GRAV_RELATIVE = 0x00800000
    const val GRAV_START = GRAV_RELATIVE or GRAV_LEFT      // 0x800003
    const val GRAV_END = GRAV_RELATIVE or GRAV_RIGHT       // 0x800005
    const val LAYOUT_DIRECTION_RTL = 1

    // Old version used xor instead of and-not and an if/if chain instead of if/else-if,
    // so a START->RIGHT rewrite could wrongly re-trigger the END branch.
    fun getAbsoluteGravity(gravity: Int, layoutDirection: Int): Int {
        var r = gravity
        if (r and GRAV_RELATIVE != 0) {
            if (r and GRAV_START == GRAV_START) {
                r = r and GRAV_START.inv()
                r = r or if (layoutDirection == LAYOUT_DIRECTION_RTL) GRAV_RIGHT else GRAV_LEFT
            } else if (r and GRAV_END == GRAV_END) {
                r = r and GRAV_END.inv()
                r = r or if (layoutDirection == LAYOUT_DIRECTION_RTL) GRAV_LEFT else GRAV_RIGHT
            }
            r = r and GRAV_RELATIVE.inv()
        }
        return r
    }

    // --- TypedValue complex floats ------------------------------------------
    private val RADIX_MULTS = floatArrayOf(
        1.0f / (1 shl 8), 1.0f / (1 shl 15), 1.0f / (1 shl 23), 1.0f / (1 shl 31),
    )
    fun complexToFloat(complex: Int): Float = (complex and -0x100) * RADIX_MULTS[(complex shr 4) and 3]
    fun complexToFraction(data: Int, base: Float, pbase: Float): Float = when (data and 0xf) {
        0 -> complexToFloat(data) * base          // COMPLEX_UNIT_FRACTION
        1 -> complexToFloat(data) * pbase         // COMPLEX_UNIT_FRACTION_PARENT
        else -> 0f
    }

    // --- KeyEvent.keyCodeFromString -----------------------------------------
    // Maps "KEYCODE_<NAME>" to its keycode. Unknown names fall through to Integer.parse,
    // then to KEYCODE_UNKNOWN (0) exactly like AOSP. Covers the standard keycode set.
    private val KEYCODES: Map<String, Int> = buildMap {
        val base = listOf(
            "UNKNOWN", "SOFT_LEFT", "SOFT_RIGHT", "HOME", "BACK", "CALL", "ENDCALL",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "STAR", "POUND", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
            "VOLUME_UP", "VOLUME_DOWN", "POWER", "CAMERA", "CLEAR",
        )
        base.forEachIndexed { i, n -> put(n, i) }          // 0..28
        for (c in 'A'..'Z') put(c.toString(), 29 + (c - 'A'))   // A=29 .. Z=54
        val after = listOf(
            "COMMA", "PERIOD", "ALT_LEFT", "ALT_RIGHT", "SHIFT_LEFT", "SHIFT_RIGHT", "TAB", "SPACE",
            "SYM", "EXPLORER", "ENVELOPE", "ENTER", "DEL", "GRAVE", "MINUS", "EQUALS",
            "LEFT_BRACKET", "RIGHT_BRACKET", "BACKSLASH", "SEMICOLON", "APOSTROPHE", "SLASH", "AT",
            "NUM", "HEADSETHOOK", "FOCUS", "PLUS", "MENU", "NOTIFICATION", "SEARCH",
            "MEDIA_PLAY_PAUSE", "MEDIA_STOP", "MEDIA_NEXT", "MEDIA_PREVIOUS", "MEDIA_REWIND",
            "MEDIA_FAST_FORWARD", "MUTE", "PAGE_UP", "PAGE_DOWN", "PICTSYMBOLS", "SWITCH_CHARSET",
            "BUTTON_A", "BUTTON_B", "BUTTON_C", "BUTTON_X", "BUTTON_Y", "BUTTON_Z",
            "BUTTON_L1", "BUTTON_R1", "BUTTON_L2", "BUTTON_R2", "BUTTON_THUMBL", "BUTTON_THUMBR",
            "BUTTON_START", "BUTTON_SELECT", "BUTTON_MODE", "ESCAPE", "FORWARD_DEL",
            "CTRL_LEFT", "CTRL_RIGHT", "CAPS_LOCK", "SCROLL_LOCK", "META_LEFT", "META_RIGHT",
            "FUNCTION", "SYSRQ", "BREAK", "MOVE_HOME", "MOVE_END", "INSERT", "FORWARD",
            "MEDIA_PLAY", "MEDIA_PAUSE", "MEDIA_CLOSE", "MEDIA_EJECT", "MEDIA_RECORD",
        )
        after.forEachIndexed { i, n -> put(n, 55 + i) }    // COMMA=55 .. MEDIA_RECORD=130
        for (i in 1..12) put("F$i", 130 + i)               // F1=131 .. F12=142
        put("NUM_LOCK", 143)
        for (i in 0..9) put("NUMPAD_$i", 144 + i)          // NUMPAD_0=144 .. NUMPAD_9=153
        val np = listOf(
            "NUMPAD_DIVIDE", "NUMPAD_MULTIPLY", "NUMPAD_SUBTRACT", "NUMPAD_ADD", "NUMPAD_DOT",
            "NUMPAD_COMMA", "NUMPAD_ENTER", "NUMPAD_EQUALS", "NUMPAD_LEFT_PAREN", "NUMPAD_RIGHT_PAREN",
            "VOLUME_MUTE", "INFO", "CHANNEL_UP", "CHANNEL_DOWN", "ZOOM_IN", "ZOOM_OUT",
            "TV", "WINDOW", "GUIDE", "DVR", "BOOKMARK", "CAPTIONS", "SETTINGS",
            "TV_POWER", "TV_INPUT", "STB_POWER", "STB_INPUT", "AVR_POWER", "AVR_INPUT",
            "PROG_RED", "PROG_GREEN", "PROG_YELLOW", "PROG_BLUE", "APP_SWITCH",
        )
        np.forEachIndexed { i, n -> put(n, 154 + i) }      // NUMPAD_DIVIDE=154 .. APP_SWITCH=187
        for (i in 1..16) put("BUTTON_$i", 187 + i)         // BUTTON_1=188 .. BUTTON_16=203
        val tail = listOf(
            "LANGUAGE_SWITCH", "MANNER_MODE", "3D_MODE", "CONTACTS", "CALENDAR", "MUSIC", "CALCULATOR",
            "ZENKAKU_HANKAKU", "EISU", "MUHENKAN", "HENKAN", "KATAKANA_HIRAGANA", "YEN", "RO", "KANA",
            "ASSIST", "BRIGHTNESS_DOWN", "BRIGHTNESS_UP", "MEDIA_AUDIO_TRACK", "SLEEP", "WAKEUP", "PAIRING",
            "MEDIA_TOP_MENU", "11", "12", "LAST_CHANNEL", "TV_DATA_SERVICE", "VOICE_ASSIST",
        )
        tail.forEachIndexed { i, n -> put(n, 204 + i) }    // LANGUAGE_SWITCH=204 .. VOICE_ASSIST=231
    }

    fun keyCodeFromString(symbolicName: String): Int {
        var name = symbolicName
        if (name.startsWith("KEYCODE_")) {
            name = name.removePrefix("KEYCODE_")
            KEYCODES[name]?.let { if (it > 0 || name == "UNKNOWN") return it }
        }
        return name.toIntOrNull() ?: 0
    }
}

/**
 * Monotonic virtual clock so timing loops terminate instead of burning the step budget.
 * uptime advances by at least one tick on every read and by the full amount on sleep().
 */
internal object VirtualClock {
    private const val BOOT_EPOCH_MS = 1_700_000_000_000L
    private val uptime = java.util.concurrent.atomic.AtomicLong(10_000L)

    fun uptimeMillis(): Long = uptime.addAndGet(1L)
    fun elapsedRealtime(): Long = uptime.addAndGet(1L)
    fun elapsedRealtimeNanos(): Long = uptime.get() * 1_000_000L
    fun currentThreadTimeMillis(): Long = uptime.get()
    fun currentTimeMillis(): Long = BOOT_EPOCH_MS + uptime.addAndGet(1L)
    fun nanoTime(): Long = uptime.get() * 1_000_000L
    fun advance(ms: Long) { if (ms > 0) uptime.addAndGet(ms) }
}
