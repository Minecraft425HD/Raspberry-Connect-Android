package com.raspberryconnect.terminal.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    private val ESC: Byte = 0x1B

    /** Builds a CSI escape sequence, e.g. csi("2K") -> ESC [ 2 K. */
    private fun csi(suffix: String): ByteArray = byteArrayOf(ESC, '['.code.toByte()) + suffix.toByteArray()

    private fun osc(body: String): ByteArray = byteArrayOf(ESC, ']'.code.toByte()) + body.toByteArray() + byteArrayOf(0x07)

    private fun bytesOf(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (part in parts) {
            when (part) {
                is String -> out.write(part.toByteArray())
                is ByteArray -> out.write(part)
                else -> error("unsupported part type")
            }
        }
        return out.toByteArray()
    }

    private fun textOf(emu: TerminalEmulator, row: Int): String =
        emu.rowAt(row).joinToString("") { it.char.toString() }.trimEnd()

    @Test
    fun `plain text is written left to right`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed("hello".toByteArray())
        assertEquals("hello", textOf(emu, 0))
        assertEquals(5, emu.cursorCol)
        assertEquals(0, emu.cursorRow)
    }

    @Test
    fun `carriage return and newline behave like a real terminal`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed("foo\r\nbar".toByteArray())
        assertEquals("foo", textOf(emu, 0))
        assertEquals("bar", textOf(emu, 1))
    }

    @Test
    fun `bash-style history redraw via carriage-return plus erase-in-line renders correctly`() {
        // This is exactly what bash/zsh readline sends when the user presses the Up arrow
        // to recall the previous command: return to column 0, erase to end of line, print
        // the recalled command. If this works, arrow-up history recall renders correctly.
        val emu = TerminalEmulator(40, 5)
        emu.feed(bytesOf("$ ls -la", "\r", csi("0K"), "$ git status"))
        assertEquals("$ git status", textOf(emu, 0))
    }

    @Test
    fun `cursor movement sequences move the cursor without printing`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed(bytesOf("12345", csi("3D")))
        assertEquals(2, emu.cursorCol)
        emu.feed(csi("2C"))
        assertEquals(4, emu.cursorCol)
    }

    @Test
    fun `cursor position CSI H places cursor at given row and column`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed(csi("3;5H"))
        assertEquals(2, emu.cursorRow)
        assertEquals(4, emu.cursorCol)
    }

    @Test
    fun `erase in line mode 2 clears the whole line`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed(bytesOf("abcdef", csi("2K")))
        assertEquals("", textOf(emu, 0))
    }

    @Test
    fun `backspace moves cursor left without erasing`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed("ab".toByteArray())
        emu.feed(byteArrayOf(0x08))
        assertEquals(1, emu.cursorCol)
    }

    @Test
    fun `line feed at bottom margin scrolls the screen and keeps scrollback`() {
        val emu = TerminalEmulator(10, 2)
        emu.feed("line1\r\nline2\r\nline3".toByteArray())
        assertEquals(1, emu.scrollback.size)
        assertEquals("line2", textOf(emu, 0))
        assertEquals("line3", textOf(emu, 1))
    }

    @Test
    fun `sgr sets and resets bold and colors`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed(bytesOf(csi("1;31m"), "X", csi("0m"), "Y"))
        val bold = emu.rowAt(0)[0]
        assertTrue(bold.bold)
        assertEquals(1, bold.fg) // red
        val reset = emu.rowAt(0)[1]
        assertFalse(reset.bold)
        assertEquals(TerminalCell.DEFAULT, reset.fg)
    }

    @Test
    fun `256 color palette index is applied`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed(bytesOf(csi("38;5;200m"), "Z"))
        assertEquals(200, emu.rowAt(0)[0].fg)
    }

    @Test
    fun `application cursor keys mode is tracked from DECCKM`() {
        val emu = TerminalEmulator(20, 5)
        assertFalse(emu.applicationCursorKeys)
        emu.feed(csi("?1h"))
        assertTrue(emu.applicationCursorKeys)
        emu.feed(csi("?1l"))
        assertFalse(emu.applicationCursorKeys)
    }

    @Test
    fun `alternate screen buffer swap clears and restores independently`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed("main screen".toByteArray())
        emu.feed(csi("?1049h")) // enter alt screen
        assertTrue(emu.alternateScreenActive)
        assertEquals("", textOf(emu, 0))
        emu.feed("alt screen".toByteArray())
        assertEquals("alt screen", textOf(emu, 0))
        emu.feed(csi("?1049l")) // leave alt screen
        assertFalse(emu.alternateScreenActive)
        assertEquals("main screen", textOf(emu, 0))
    }

    @Test
    fun `resize preserves existing content in the overlapping region`() {
        val emu = TerminalEmulator(10, 5)
        emu.feed("hello".toByteArray())
        emu.resize(20, 10)
        assertEquals(20, emu.cols)
        assertEquals(10, emu.rows)
        assertEquals("hello", textOf(emu, 0))
    }

    @Test
    fun `tab advances to the next 8-column stop`() {
        val emu = TerminalEmulator(40, 5)
        emu.feed(bytesOf("a", "\t", "b"))
        assertEquals('a', emu.rowAt(0)[0].char)
        assertEquals('b', emu.rowAt(0)[8].char)
    }

    @Test
    fun `cursor visibility toggles with DECTCEM`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed(csi("?25l"))
        assertFalse(emu.cursorVisible)
        emu.feed(csi("?25h"))
        assertTrue(emu.cursorVisible)
    }

    @Test
    fun `osc window title sequence is consumed without corrupting the stream`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed(bytesOf(osc("0;my title"), "after"))
        assertEquals("after", textOf(emu, 0))
    }

    @Test
    fun `insert and delete characters shift the line correctly`() {
        val emu = TerminalEmulator(20, 5)
        emu.feed(bytesOf("abcdef", csi("3D"))) // move back to col 3 (char 'd')
        emu.feed(csi("2P")) // delete 2 chars ('d','e')
        assertEquals("abcf", textOf(emu, 0))
    }
}
