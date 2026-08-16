package com.raspberryconnect.terminal.terminal

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyMapperTest {

    private val ESC: Byte = 0x1B

    @Test
    fun `arrow up sends CSI A in normal cursor key mode`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_DPAD_UP, 0, false, false, false, applicationCursorKeys = false)
        assertArrayEquals(byteArrayOf(ESC, '['.code.toByte(), 'A'.code.toByte()), bytes)
    }

    @Test
    fun `arrow up sends SS3 A in application cursor key mode`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_DPAD_UP, 0, false, false, false, applicationCursorKeys = true)
        assertArrayEquals(byteArrayOf(ESC, 'O'.code.toByte(), 'A'.code.toByte()), bytes)
    }

    @Test
    fun `all four arrow keys map to their CSI letters`() {
        val cases = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to 'A',
            KeyEvent.KEYCODE_DPAD_DOWN to 'B',
            KeyEvent.KEYCODE_DPAD_RIGHT to 'C',
            KeyEvent.KEYCODE_DPAD_LEFT to 'D'
        )
        for ((keyCode, expected) in cases) {
            val bytes = KeyMapper.map(keyCode, 0, false, false, false, applicationCursorKeys = false)
            assertArrayEquals(byteArrayOf(ESC, '['.code.toByte(), expected.code.toByte()), bytes)
        }
    }

    @Test
    fun `enter sends carriage return`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_ENTER, 0, false, false, false, false)
        assertArrayEquals(byteArrayOf('\r'.code.toByte()), bytes)
    }

    @Test
    fun `backspace sends DEL byte 0x7F`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_DEL, 0, false, false, false, false)
        assertArrayEquals(byteArrayOf(0x7F.toByte()), bytes)
    }

    @Test
    fun `tab sends 0x09`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_TAB, 0, false, false, false, false)
        assertArrayEquals(byteArrayOf(0x09), bytes)
    }

    @Test
    fun `ctrl+c sends 0x03`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_C, 'c'.code, ctrl = true, shift = false, alt = false, applicationCursorKeys = false)
        assertArrayEquals(byteArrayOf(0x03), bytes)
    }

    @Test
    fun `ctrl+d sends 0x04 (EOF)`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_D, 'd'.code, ctrl = true, shift = false, alt = false, applicationCursorKeys = false)
        assertArrayEquals(byteArrayOf(0x04), bytes)
    }

    @Test
    fun `ctrl+l sends 0x0C (clear screen)`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_L, 'l'.code, ctrl = true, shift = false, alt = false, applicationCursorKeys = false)
        assertArrayEquals(byteArrayOf(0x0C), bytes)
    }

    @Test
    fun `plain printable character is UTF-8 encoded`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_A, 'a'.code, ctrl = false, shift = false, alt = false, applicationCursorKeys = false)
        assertArrayEquals("a".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun `non-ascii unicode character is UTF-8 encoded`() {
        val bytes = KeyMapper.map(0, 'ü'.code, ctrl = false, shift = false, alt = false, applicationCursorKeys = false)
        assertArrayEquals("ü".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun `unmapped key with no unicode char returns null`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_UNKNOWN, 0, ctrl = false, shift = false, alt = false, applicationCursorKeys = false)
        assertNull(bytes)
    }

    @Test
    fun `function key F5 sends CSI 15 tilde`() {
        val bytes = KeyMapper.map(KeyEvent.KEYCODE_F5, 0, false, false, false, false)
        assertArrayEquals(byteArrayOf(ESC, '['.code.toByte(), '1'.code.toByte(), '5'.code.toByte(), '~'.code.toByte()), bytes)
    }

    @Test
    fun `home and end keys send expected sequences`() {
        assertArrayEquals(
            byteArrayOf(ESC, '['.code.toByte(), 'H'.code.toByte()),
            KeyMapper.map(KeyEvent.KEYCODE_MOVE_HOME, 0, false, false, false, false)
        )
        assertArrayEquals(
            byteArrayOf(ESC, '['.code.toByte(), 'F'.code.toByte()),
            KeyMapper.map(KeyEvent.KEYCODE_MOVE_END, 0, false, false, false, false)
        )
    }

    @Test
    fun `controlByteForChar computes ctrl codes for letters`() {
        assertEquals(0x03.toByte(), KeyMapper.controlByteForChar('c'))
        assertEquals(0x1A.toByte(), KeyMapper.controlByteForChar('z'))
        assertEquals(0x01.toByte(), KeyMapper.controlByteForChar('A'))
    }

    @Test
    fun `isControlKey recognizes navigation keys but not letters`() {
        assertEquals(true, KeyMapper.isControlKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(true, KeyMapper.isControlKey(KeyEvent.KEYCODE_TAB))
        assertEquals(false, KeyMapper.isControlKey(KeyEvent.KEYCODE_A))
    }
}
