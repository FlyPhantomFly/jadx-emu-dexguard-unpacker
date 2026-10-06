package jadx.emu.ext.dexguard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks in the AOSP values for the opaque-constant math. These are the regressions that the
 * original stubs got wrong; the expected numbers come straight from the framework source.
 */
class AospMathTest {

    @Test fun getDefaultSize_handlesAtMost() {
        val atMost = AospMath.makeMeasureSpec(50, AospMath.AT_MOST)
        val exactly = AospMath.makeMeasureSpec(50, AospMath.EXACTLY)
        val unspec = AospMath.makeMeasureSpec(50, AospMath.UNSPECIFIED)
        assertEquals(50, AospMath.getDefaultSize(100, atMost))   // was wrongly 100
        assertEquals(50, AospMath.getDefaultSize(100, exactly))
        assertEquals(100, AospMath.getDefaultSize(100, unspec))
    }

    @Test fun resolveSizeAndState_tooSmallFlag() {
        val atMost = AospMath.makeMeasureSpec(10, AospMath.AT_MOST)
        val r = AospMath.resolveSizeAndState(100, atMost, 0)
        assertEquals(10 or AospMath.MEASURED_STATE_TOO_SMALL, r)
    }

    @Test fun resolveOpacity_matchesPixelFormatOrder() {
        assertEquals(-1, AospMath.resolveOpacity(-1, -1))   // OPAQUE,OPAQUE
        assertEquals(0, AospMath.resolveOpacity(0, -1))     // UNKNOWN wins
        assertEquals(-3, AospMath.resolveOpacity(-3, -2))   // TRANSLUCENT over TRANSPARENT
        assertEquals(-2, AospMath.resolveOpacity(-2, -1))   // TRANSPARENT over OPAQUE
        assertEquals(-1, AospMath.resolveOpacity(-1, -1))   // both OPAQUE
    }

    @Test fun metaState() {
        assertEquals(0x770FF, AospMath.META_MODIFIER_MASK)
        assertEquals(0x7770FF, AospMath.META_ALL_MASK)
        // a bare left-shift should imply SHIFT_ON after normalization
        val norm = AospMath.normalizeMetaState(AospMath.META_SHIFT_LEFT_ON)
        assertTrue(norm and AospMath.META_SHIFT_ON != 0)
        assertEquals(AospMath.META_SHIFT_LEFT_ON or AospMath.META_SHIFT_ON, norm)
    }

    @Test fun packedPositions() {
        val child = AospMath.packedForChild(2, 3)
        assertEquals(1, AospMath.packedType(child))
        assertEquals(2, AospMath.packedGroup(child))
        assertEquals(3, AospMath.packedChild(child))
        val group = AospMath.packedForGroup(7)
        assertEquals(0, AospMath.packedType(group))
        assertEquals(7, AospMath.packedGroup(group))
        assertEquals(-1, AospMath.packedChild(group))
        // null sentinel is 0xFFFFFFFF, not -1
        assertEquals(2, AospMath.packedType(AospMath.PACKED_NULL))
        assertEquals(-1, AospMath.packedGroup(AospMath.PACKED_NULL))
    }

    @Test fun absoluteGravity() {
        assertEquals(AospMath.GRAV_RIGHT, AospMath.getAbsoluteGravity(AospMath.GRAV_START, AospMath.LAYOUT_DIRECTION_RTL))
        assertEquals(AospMath.GRAV_LEFT, AospMath.getAbsoluteGravity(AospMath.GRAV_START, 0))
        assertEquals(AospMath.GRAV_LEFT, AospMath.getAbsoluteGravity(AospMath.GRAV_END, AospMath.LAYOUT_DIRECTION_RTL))
        assertEquals(AospMath.GRAV_RIGHT, AospMath.getAbsoluteGravity(AospMath.GRAV_END, 0))
    }

    @Test fun color() {
        assertEquals(0xFF112233.toInt(), AospMath.rgb(0x11, 0x22, 0x33))
        assertEquals(0x44112233, AospMath.argb(0x44, 0x11, 0x22, 0x33))
        assertEquals(0x44, AospMath.alpha(0x44112233))
        assertEquals(0x22, AospMath.green(0x44112233))
    }

    @Test fun keyCodes() {
        assertEquals(29, AospMath.keyCodeFromString("KEYCODE_A"))
        assertEquals(66, AospMath.keyCodeFromString("KEYCODE_ENTER"))
        assertEquals(4, AospMath.keyCodeFromString("KEYCODE_BACK"))
        assertEquals(131, AospMath.keyCodeFromString("KEYCODE_F1"))
        assertEquals(0, AospMath.keyCodeFromString("KEYCODE_TOTALLY_MADE_UP"))
        assertEquals(42, AospMath.keyCodeFromString("42"))
    }

    @Test fun virtualClockIsMonotonic() {
        val a = VirtualClock.uptimeMillis()
        val b = VirtualClock.uptimeMillis()
        assertTrue(b > a)
    }

    @Test fun dexBytesRepairChecksum() {
        // minimal 0x70-byte DEX-shaped buffer with valid magic
        val b = ByteArray(0x70)
        "dex\n035\u0000".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        assertTrue(DexBytes.isDex(b))
        val repaired = DexBytes.repair(b)
        // checksum and signature fields are now non-zero
        assertTrue((0x08 until 0x0c).any { repaired[it].toInt() != 0 })
        assertTrue((0x0c until 0x20).any { repaired[it].toInt() != 0 })
    }
}
