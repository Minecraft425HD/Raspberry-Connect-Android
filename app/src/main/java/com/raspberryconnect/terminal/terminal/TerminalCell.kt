package com.raspberryconnect.terminal.terminal

/** A single character cell. [fg]/[bg] are palette indices 0-255, or [DEFAULT] to inherit theme colors. */
data class TerminalCell(
    val char: Char = ' ',
    val fg: Int = DEFAULT,
    val bg: Int = DEFAULT,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false
) {
    companion object {
        const val DEFAULT = -1
        val BLANK = TerminalCell()
    }
}
