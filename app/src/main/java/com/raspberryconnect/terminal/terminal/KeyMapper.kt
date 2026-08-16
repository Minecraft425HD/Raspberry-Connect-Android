package com.raspberryconnect.terminal.terminal

import android.view.KeyEvent
import java.nio.charset.StandardCharsets

/**
 * Translates Android key events into the exact byte sequences a real terminal would
 * send over the wire. Pure function of primitives (no [KeyEvent] object involved in
 * the signature) so the mapping table can be unit tested without Robolectric.
 *
 * This is the other half of solving the "arrow-up doesn't recall history" complaint:
 * once bash has a real PTY (see [TerminalEmulator]/SshjTerminalTransport), it only
 * works if the up arrow actually reaches it as ESC [ A (or ESC O A in application
 * cursor key mode) - exactly what a desktop terminal emulator sends.
 */
object KeyMapper {

    private val ESC: String = String(charArrayOf(0x1B.toChar()))

    fun map(
        keyCode: Int,
        unicodeChar: Int,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        applicationCursorKeys: Boolean
    ): ByteArray? {
        arrowSequence(keyCode, applicationCursorKeys)?.let { return it }
        functionKeySequence(keyCode)?.let { return it }
        navigationSequence(keyCode)?.let { return it }

        if (ctrl) {
            controlByte(keyCode, unicodeChar)?.let { return byteArrayOf(it) }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> byteArrayOf('\r'.code.toByte())
            KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F.toByte())
            KeyEvent.KEYCODE_FORWARD_DEL -> csi("3~")
            KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
            KeyEvent.KEYCODE_SPACE -> byteArrayOf(' '.code.toByte())
            else -> if (unicodeChar > 0) {
                String(Character.toChars(unicodeChar)).toByteArray(StandardCharsets.UTF_8)
            } else {
                null
            }
        }
    }

    private fun arrowSequence(keyCode: Int, applicationCursorKeys: Boolean): ByteArray? {
        val final = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "D"
            else -> return null
        }
        val introducer = if (applicationCursorKeys) "O" else "["
        return (ESC + introducer + final).toByteArray(StandardCharsets.US_ASCII)
    }

    private fun navigationSequence(keyCode: Int): ByteArray? {
        val suffix = when (keyCode) {
            KeyEvent.KEYCODE_MOVE_HOME -> "[H"
            KeyEvent.KEYCODE_MOVE_END -> "[F"
            KeyEvent.KEYCODE_PAGE_UP -> "[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "[6~"
            KeyEvent.KEYCODE_INSERT -> "[2~"
            else -> return null
        }
        return (ESC + suffix).toByteArray(StandardCharsets.US_ASCII)
    }

    private fun functionKeySequence(keyCode: Int): ByteArray? {
        val suffix = when (keyCode) {
            KeyEvent.KEYCODE_F1 -> "OP"
            KeyEvent.KEYCODE_F2 -> "OQ"
            KeyEvent.KEYCODE_F3 -> "OR"
            KeyEvent.KEYCODE_F4 -> "OS"
            KeyEvent.KEYCODE_F5 -> "[15~"
            KeyEvent.KEYCODE_F6 -> "[17~"
            KeyEvent.KEYCODE_F7 -> "[18~"
            KeyEvent.KEYCODE_F8 -> "[19~"
            KeyEvent.KEYCODE_F9 -> "[20~"
            KeyEvent.KEYCODE_F10 -> "[21~"
            KeyEvent.KEYCODE_F11 -> "[23~"
            KeyEvent.KEYCODE_F12 -> "[24~"
            else -> return null
        }
        return (ESC + suffix).toByteArray(StandardCharsets.US_ASCII)
    }

    /** Returns the control byte for Ctrl+<char>, e.g. 'c' -> 0x03, or null if not controllable. */
    fun controlByteForChar(char: Char): Byte? = controlByte(keyCode = -1, unicodeChar = char.code)

    /** Returns the control byte for Ctrl+<key>, or null if this isn't a controllable key. */
    private fun controlByte(keyCode: Int, unicodeChar: Int): Byte? {
        val letter = when {
            unicodeChar in 'a'.code..'z'.code -> unicodeChar
            unicodeChar in 'A'.code..'Z'.code -> unicodeChar + 32
            keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                'a'.code + (keyCode - KeyEvent.KEYCODE_A)
            else -> -1
        }
        if (letter in 'a'.code..'z'.code) {
            return (letter - 'a'.code + 1).toByte()
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x1B
            KeyEvent.KEYCODE_BACKSLASH -> 0x1C
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x1D
            KeyEvent.KEYCODE_SPACE -> 0x00
            else -> null
        }?.toByte()
    }

    private fun csi(suffix: String): ByteArray = (ESC + "[" + suffix).toByteArray(StandardCharsets.US_ASCII)

    private val controlKeyCodes = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_INSERT,
        KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5,
        KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10,
        KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12
    )

    /** Keys that must always be intercepted as control sequences, even from a soft keyboard. */
    fun isControlKey(keyCode: Int): Boolean = keyCode in controlKeyCodes
}
