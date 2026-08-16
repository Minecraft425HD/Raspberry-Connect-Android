package com.raspberryconnect.terminal.terminal

/**
 * A compact VT100/xterm-subset terminal emulator: it turns the byte stream coming
 * back from the remote shell into a grid of [TerminalCell]s.
 *
 * This is what makes command history recall (arrow-up) and in-place line editing
 * work correctly: bash/zsh redraw the prompt line using real cursor-movement and
 * erase-in-line escape sequences over the PTY, and this class interprets them the
 * same way a desktop terminal would - instead of the browser's plain text box that
 * has no concept of "move left three cells and erase to end of line".
 *
 * Deliberately free of any android.* imports so it is fully unit testable on the JVM.
 */
class TerminalEmulator(cols: Int, rows: Int) {

    var cols = cols
        private set
    var rows = rows
        private set

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set

    var cursorVisible = true
        private set

    /** DECCKM - when true, arrow keys must be encoded as SS3 (ESC O A) instead of CSI (ESC [ A). */
    var applicationCursorKeys = false
        private set

    /** Alternate screen buffer active (full-screen apps like vim/htop). */
    var alternateScreenActive = false
        private set

    val scrollback: List<Array<TerminalCell>> get() = scrollbackLines

    private var screen = newBlankScreen(cols, rows)
    private var altScreen = newBlankScreen(cols, rows)
    private val scrollbackLines = ArrayDeque<Array<TerminalCell>>()
    private val maxScrollback = 2000

    private var savedCursorRow = 0
    private var savedCursorCol = 0

    // Current SGR pen state.
    private var curFg = TerminalCell.DEFAULT
    private var curBg = TerminalCell.DEFAULT
    private var curBold = false
    private var curItalic = false
    private var curUnderline = false
    private var curReverse = false

    // Parser state machine.
    private var parserState = State.GROUND
    private val params = IntArray(MAX_PARAMS)
    private var paramCount = 0
    private var currentParamStarted = false
    private var privateMarker = false
    private val oscBuffer = StringBuilder()

    private enum class State { GROUND, ESCAPE, CSI, OSC, OSC_ESCAPE }

    fun feed(data: ByteArray, length: Int = data.size) {
        for (i in 0 until length) {
            processByte(data[i].toInt() and 0xFF)
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        screen = resizedCopy(screen, newCols, newRows)
        altScreen = resizedCopy(altScreen, newCols, newRows)
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
        cols = newCols
        rows = newRows
    }

    fun rowAt(row: Int): Array<TerminalCell> = screen[row]

    private fun processByte(b: Int) {
        when (parserState) {
            State.GROUND -> processGround(b)
            State.ESCAPE -> processEscape(b)
            State.CSI -> processCsi(b)
            State.OSC -> processOsc(b)
            State.OSC_ESCAPE -> processOscEscape(b)
        }
    }

    private fun processGround(b: Int) {
        when (b) {
            0x1B -> parserState = State.ESCAPE
            '\r'.code -> cursorCol = 0
            '\n'.code -> lineFeed()
            '\b'.code -> cursorCol = (cursorCol - 1).coerceAtLeast(0)
            '\t'.code -> cursorCol = (((cursorCol / TAB_WIDTH) + 1) * TAB_WIDTH).coerceAtMost(cols - 1)
            0x07 -> { /* bell: ignored */ }
            else -> if (b >= 0x20) putChar(b.toChar())
        }
    }

    private fun processEscape(b: Int) {
        when (b.toChar()) {
            '[' -> {
                paramCount = 0
                currentParamStarted = false
                privateMarker = false
                params.fill(0)
                parserState = State.CSI
            }
            ']' -> {
                oscBuffer.clear()
                parserState = State.OSC
            }
            '7' -> { savedCursorRow = cursorRow; savedCursorCol = cursorCol; parserState = State.GROUND }
            '8' -> { cursorRow = savedCursorRow; cursorCol = savedCursorCol; parserState = State.GROUND }
            'M' -> { reverseLineFeed(); parserState = State.GROUND }
            'c' -> { reset(); parserState = State.GROUND }
            'O' -> parserState = State.GROUND // SS3 from host is not meaningful; swallow next byte via ground.
            else -> parserState = State.GROUND
        }
    }

    private fun processCsi(b: Int) {
        val c = b.toChar()
        when {
            c == '?' && paramCount == 0 && !currentParamStarted -> privateMarker = true
            c.isDigit() -> {
                if (paramCount == 0) paramCount = 1
                currentParamStarted = true
                val idx = (paramCount - 1).coerceIn(0, MAX_PARAMS - 1)
                params[idx] = (params[idx] * 10 + (c - '0')).coerceAtMost(9999)
            }
            c == ';' -> {
                if (paramCount == 0) paramCount = 1
                if (paramCount < MAX_PARAMS) paramCount++
                currentParamStarted = false
            }
            b in 0x40..0x7E -> {
                dispatchCsi(c)
                parserState = State.GROUND
            }
            else -> { /* ignore intermediates we don't support */ }
        }
    }

    private fun processOsc(b: Int) {
        when (b) {
            0x07 -> parserState = State.GROUND // BEL terminates OSC
            0x1B -> parserState = State.OSC_ESCAPE
            else -> oscBuffer.append(b.toChar())
        }
    }

    private fun processOscEscape(b: Int) {
        // Expect '\\' to complete the ST (ESC \\) terminator; otherwise treat as data.
        if (b.toChar() == '\\') {
            parserState = State.GROUND
        } else {
            oscBuffer.append(0x1B.toChar()).append(b.toChar())
            parserState = State.OSC
        }
    }

    // Returns the raw param, or `default` if absent/zero (matches the common terminal
    // convention where 0 and "absent" both mean "use default").
    private fun p(index: Int, default: Int): Int {
        if (index >= paramCount) return default
        val v = params[index]
        return if (v == 0) default else v
    }

    private fun dispatchCsi(final: Char) {
        when (final) {
            'A' -> cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + p(0, 1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + p(0, 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - p(0, 1)).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + p(0, 1)).coerceAtMost(rows - 1); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(0); cursorCol = 0 }
            'G' -> cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'd' -> cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
            'J' -> eraseInDisplay(p(0, 0))
            'K' -> eraseInLine(p(0, 0))
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'S' -> repeat(p(0, 1)) { lineFeed() }
            'T' -> repeat(p(0, 1)) { reverseLineFeed() }
            's' -> { savedCursorRow = cursorRow; savedCursorCol = cursorCol }
            'u' -> { cursorRow = savedCursorRow; cursorCol = savedCursorCol }
            'm' -> applySgr()
            'h' -> setMode(true)
            'l' -> setMode(false)
            else -> { /* unsupported final byte: no-op */ }
        }
    }

    private fun setMode(enable: Boolean) {
        if (!privateMarker) return
        for (i in 0 until paramCount) {
            when (params[i]) {
                25 -> cursorVisible = enable
                1 -> applicationCursorKeys = enable
                1049, 1047, 47 -> switchAlternateScreen(enable)
            }
        }
    }

    private fun switchAlternateScreen(enable: Boolean) {
        if (enable == alternateScreenActive) return
        val tmp = screen
        screen = altScreen
        altScreen = tmp
        if (enable) clearScreenCells()
        alternateScreenActive = enable
        cursorRow = 0
        cursorCol = 0
    }

    private fun applySgr() {
        if (paramCount == 0) {
            resetPen()
            return
        }
        var i = 0
        while (i < paramCount) {
            when (val code = params[i]) {
                0 -> resetPen()
                1 -> curBold = true
                3 -> curItalic = true
                4 -> curUnderline = true
                7 -> curReverse = true
                22 -> curBold = false
                23 -> curItalic = false
                24 -> curUnderline = false
                27 -> curReverse = false
                39 -> curFg = TerminalCell.DEFAULT
                49 -> curBg = TerminalCell.DEFAULT
                in 30..37 -> curFg = code - 30
                in 90..97 -> curFg = code - 90 + 8
                in 40..47 -> curBg = code - 40
                in 100..107 -> curBg = code - 100 + 8
                38, 48 -> {
                    val isFg = code == 38
                    if (i + 1 < paramCount && params[i + 1] == 5 && i + 2 < paramCount) {
                        val idx = params[i + 2]
                        if (isFg) curFg = idx else curBg = idx
                        i += 2
                    } else if (i + 1 < paramCount && params[i + 1] == 2 && i + 4 < paramCount) {
                        val idx = TerminalColors.nearestPaletteIndex(params[i + 2], params[i + 3], params[i + 4])
                        if (isFg) curFg = idx else curBg = idx
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun resetPen() {
        curFg = TerminalCell.DEFAULT
        curBg = TerminalCell.DEFAULT
        curBold = false
        curItalic = false
        curUnderline = false
        curReverse = false
    }

    private fun putChar(char: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        screen[cursorRow][cursorCol] = TerminalCell(char, curFg, curBg, curBold, curItalic, curUnderline, curReverse)
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow == rows - 1) {
            if (!alternateScreenActive) {
                scrollbackLines.addLast(screen[0])
                if (scrollbackLines.size > maxScrollback) scrollbackLines.removeFirst()
            }
            for (r in 0 until rows - 1) screen[r] = screen[r + 1]
            screen[rows - 1] = blankRow(cols)
        } else {
            cursorRow++
        }
    }

    private fun reverseLineFeed() {
        if (cursorRow == 0) {
            for (r in rows - 1 downTo 1) screen[r] = screen[r - 1]
            screen[0] = blankRow(cols)
        } else {
            cursorRow--
        }
    }

    private fun eraseInLine(mode: Int) {
        val row = screen[cursorRow]
        when (mode) {
            0 -> for (c in cursorCol until cols) row[c] = TerminalCell.BLANK
            1 -> for (c in 0..cursorCol) row[c] = TerminalCell.BLANK
            2 -> for (c in 0 until cols) row[c] = TerminalCell.BLANK
        }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (r in cursorRow + 1 until rows) screen[r] = blankRow(cols)
            }
            1 -> {
                eraseInLine(1)
                for (r in 0 until cursorRow) screen[r] = blankRow(cols)
            }
            2, 3 -> clearScreenCells()
        }
    }

    private fun clearScreenCells() {
        for (r in 0 until rows) screen[r] = blankRow(cols)
    }

    private fun insertLines(count: Int) {
        val n = count.coerceAtMost(rows - cursorRow)
        for (i in 0 until n) {
            for (r in rows - 1 downTo cursorRow + 1) screen[r] = screen[r - 1]
            screen[cursorRow] = blankRow(cols)
        }
    }

    private fun deleteLines(count: Int) {
        val n = count.coerceAtMost(rows - cursorRow)
        for (i in 0 until n) {
            for (r in cursorRow until rows - 1) screen[r] = screen[r + 1]
            screen[rows - 1] = blankRow(cols)
        }
    }

    private fun insertChars(count: Int) {
        val row = screen[cursorRow]
        val n = count.coerceAtMost(cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + n) row[c] = row[c - n]
        for (c in cursorCol until (cursorCol + n).coerceAtMost(cols)) row[c] = TerminalCell.BLANK
    }

    private fun deleteChars(count: Int) {
        val row = screen[cursorRow]
        val n = count.coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cols - n) row[c] = row[c + n]
        for (c in (cols - n).coerceAtLeast(cursorCol) until cols) row[c] = TerminalCell.BLANK
    }

    private fun eraseChars(count: Int) {
        val row = screen[cursorRow]
        val n = count.coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cursorCol + n) row[c] = TerminalCell.BLANK
    }

    private fun reset() {
        clearScreenCells()
        cursorRow = 0
        cursorCol = 0
        resetPen()
        cursorVisible = true
        applicationCursorKeys = false
        alternateScreenActive = false
    }

    private fun resizedCopy(old: Array<Array<TerminalCell>>, newCols: Int, newRows: Int): Array<Array<TerminalCell>> {
        val fresh = newBlankScreen(newCols, newRows)
        for (r in 0 until minOf(old.size, newRows)) {
            for (c in 0 until minOf(old[r].size, newCols)) {
                fresh[r][c] = old[r][c]
            }
        }
        return fresh
    }

    private fun newBlankScreen(c: Int, r: Int): Array<Array<TerminalCell>> = Array(r) { blankRow(c) }

    private fun blankRow(c: Int): Array<TerminalCell> = Array(c) { TerminalCell.BLANK }

    companion object {
        private const val MAX_PARAMS = 16
        private const val TAB_WIDTH = 8
    }
}
