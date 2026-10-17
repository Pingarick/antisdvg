package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reader's inline images carry this encoder, and `android.util.Base64` is not
 * available to these tests, so the padding cases are pinned to the RFC vectors.
 */
class Base64LiteTest {

    private fun encode(text: String) = Base64Lite.encode(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `matches the rfc 4648 vectors`() {
        assertEquals("", encode(""))
        assertEquals("Zg==", encode("f"))
        assertEquals("Zm8=", encode("fo"))
        assertEquals("Zm9v", encode("foo"))
        assertEquals("Zm9vYg==", encode("foob"))
        assertEquals("Zm9vYmE=", encode("fooba"))
        assertEquals("Zm9vYmFy", encode("foobar"))
    }

    @Test
    fun `encodes high bytes without sign trouble`() {
        // A PNG signature: 0x89 is negative as a signed byte and must not leak '-'.
        assertEquals("iVBORw0KGgo=", Base64Lite.encode(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
    }
}
