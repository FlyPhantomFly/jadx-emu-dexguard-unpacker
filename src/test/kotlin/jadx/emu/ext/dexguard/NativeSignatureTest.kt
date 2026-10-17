package jadx.emu.ext.dexguard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeSignatureTest {

    @Test fun parsesArgsAndReturn() {
        assertEquals(listOf("[B", "Ljava/lang/String;"), NativeSignature.argTypes("([BLjava/lang/String;)[B"))
        assertEquals("[B", NativeSignature.returnType("([BLjava/lang/String;)[B"))
    }

    @Test fun noArgsVoid() {
        assertEquals(emptyList<String>(), NativeSignature.argTypes("()V"))
        assertEquals("V", NativeSignature.returnType("()V"))
    }

    @Test fun primitivesAndObjects() {
        assertEquals(listOf("I", "I", "[B", "Ljava/lang/Object;"), NativeSignature.argTypes("(II[BLjava/lang/Object;)Z"))
        assertEquals("Z", NativeSignature.returnType("(II[BLjava/lang/Object;)Z"))
    }

    @Test fun multiDimArrays() {
        assertEquals(listOf("[[B"), NativeSignature.argTypes("([[B)[[I"))
        assertEquals("[[I", NativeSignature.returnType("([[B)[[I"))
    }

    @Test fun wideAndChar() {
        assertEquals(listOf("J", "D", "C", "S"), NativeSignature.argTypes("(JDCS)J"))
        assertEquals("J", NativeSignature.returnType("(JDCS)J"))
    }
}
