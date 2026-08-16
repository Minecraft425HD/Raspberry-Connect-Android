package com.raspberryconnect.terminal.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.raspberryconnect.terminal.R
import kotlin.math.max
import kotlin.math.min

interface TerminalInputListener {
    fun onInput(bytes: ByteArray)
    fun onTerminalSizeChanged(cols: Int, rows: Int)
}

/**
 * Renders a [TerminalEmulator]'s screen buffer and turns keyboard/touch input into
 * the byte sequences the remote shell expects.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var emulator: TerminalEmulator? = null
        private set
    var listener: TerminalInputListener? = null

    private val defaultFg = ContextCompat.getColor(context, R.color.terminal_fg)
    private val defaultBg = ContextCompat.getColor(context, R.color.terminal_bg)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 34f
    }
    private val bgPaint = Paint()
    private val cursorPaint = Paint().apply { alpha = 160 }

    private var charWidth = 1f
    private var charHeight = 1f
    private var charAscent = 0f

    private var scrollOffset = 0 // lines back into scrollback, 0 = live bottom

    /** Set by the extra-keys row's "Ctrl" toggle; applies to the next key press only. */
    var stickyCtrl = false

    // Text selection (for copy), in view-row/column coordinates.
    private var selecting = false
    private var selStartRow = 0
    private var selStartCol = 0
    private var selEndRow = 0
    private var selEndCol = 0
    private var actionMode: ActionMode? = null
    private val selectionHighlightPaint = Paint().apply { color = 0x668AB4F8.toInt() }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (selecting) return false
            val emu = emulator ?: return false
            // GestureDetector reports distanceY as (previous - current) touch position, so
            // dragging a finger DOWN the screen - the natural gesture to reveal older
            // content above, like pulling down a chat history - yields a NEGATIVE value.
            // Flip the sign so that drag matches scroll direction.
            val lineDelta = (-distanceY / charHeight).toInt()
            if (lineDelta == 0) return false
            val maxOffset = emu.scrollback.size
            scrollOffset = (scrollOffset + lineDelta).coerceIn(0, maxOffset)
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            beginSelectionAtWord(e.x, e.y)
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selecting) {
                endSelection()
                return true
            }
            requestFocus()
            showKeyboard()
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureCharSize()
    }

    fun attach(emulator: TerminalEmulator, listener: TerminalInputListener) {
        this.emulator = emulator
        this.listener = listener
        scrollOffset = 0
        requestLayoutForCurrentSize()
        invalidate()
        requestFocus()
        post { showKeyboard() }
    }

    /** Call after feeding new bytes into the emulator to repaint and snap to the live view. */
    fun onScreenUpdated() {
        scrollOffset = 0
        invalidate()
    }

    private fun measureCharSize() {
        charWidth = textPaint.measureText("X")
        val fm = textPaint.fontMetrics
        charHeight = fm.descent - fm.ascent
        charAscent = -fm.ascent
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestLayoutForCurrentSize()
    }

    private fun requestLayoutForCurrentSize() {
        if (width == 0 || height == 0 || charWidth <= 0f || charHeight <= 0f) return
        val cols = max(1, (width / charWidth).toInt())
        val rows = max(1, (height / charHeight).toInt())
        emulator?.resize(cols, rows)
        listener?.onTerminalSizeChanged(cols, rows)
    }

    override fun onDraw(canvas: Canvas) {
        val emu = emulator ?: run {
            canvas.drawColor(defaultBg)
            return
        }
        canvas.drawColor(defaultBg)

        val rows = emu.rows
        val cols = emu.cols

        for (viewRow in 0 until rows) {
            val cells = cellsForViewRow(viewRow) ?: continue
            drawRow(canvas, cells, viewRow, cols)
        }

        if (selecting) {
            drawSelectionHighlight(canvas, cols)
        }

        if (scrollOffset == 0 && emu.cursorVisible && hasFocus()) {
            drawCursor(canvas, emu.cursorRow, emu.cursorCol)
        }
    }

    /** Resolves a view row (0 = top of the visible viewport) to its cells, honoring scrollOffset. */
    private fun cellsForViewRow(viewRow: Int): Array<TerminalCell>? {
        val emu = emulator ?: return null
        val history = emu.scrollback
        val logicalIndex = viewRow - scrollOffset
        return if (logicalIndex < 0) {
            val historyIndex = history.size + logicalIndex
            if (historyIndex in history.indices) history[historyIndex] else null
        } else if (logicalIndex < emu.rows) {
            emu.rowAt(logicalIndex)
        } else {
            null
        }
    }

    private fun drawSelectionHighlight(canvas: Canvas, cols: Int) {
        val (startRow, startCol, endRow, endCol) = normalizedSelection()
        for (viewRow in startRow..endRow) {
            val lineStartCol = if (viewRow == startRow) startCol else 0
            val lineEndCol = if (viewRow == endRow) endCol else cols - 1
            if (lineEndCol < lineStartCol) continue
            val y = viewRow * charHeight
            canvas.drawRect(
                lineStartCol * charWidth, y,
                (lineEndCol + 1) * charWidth, y + charHeight,
                selectionHighlightPaint
            )
        }
    }

    private fun drawRow(canvas: Canvas, cells: Array<TerminalCell>, viewRow: Int, cols: Int) {
        val y = viewRow * charHeight
        for (col in 0 until min(cols, cells.size)) {
            val cell = cells[col]
            val x = col * charWidth

            val fgColor = resolveColor(cell.fg, defaultFg)
            val bgColor = resolveColor(cell.bg, defaultBg)
            val (paintedFg, paintedBg) = if (cell.reverse) bgColor to fgColor else fgColor to bgColor

            if (paintedBg != defaultBg) {
                bgPaint.color = paintedBg
                canvas.drawRect(x, y, x + charWidth, y + charHeight, bgPaint)
            }

            if (cell.char != ' ') {
                textPaint.color = paintedFg
                textPaint.isFakeBoldText = cell.bold
                textPaint.textSkewX = if (cell.italic) -0.25f else 0f
                textPaint.isUnderlineText = cell.underline
                canvas.drawText(cell.char.toString(), x, y + charAscent, textPaint)
            }
        }
    }

    private fun drawCursor(canvas: Canvas, row: Int, col: Int) {
        val x = col * charWidth
        val y = row * charHeight
        cursorPaint.color = defaultFg
        canvas.drawRect(x, y, x + charWidth, y + charHeight, cursorPaint)
    }

    private fun resolveColor(index: Int, default: Int): Int =
        if (index == TerminalCell.DEFAULT) default else TerminalColors.paletteColor(index)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isFocused) requestFocus()
        if (selecting && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val (row, col) = viewRowColAt(event.x, event.y)
            selEndRow = row
            selEndCol = col
            invalidate()
            return true
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** Custom Views don't get the soft keyboard automatically on focus like EditText does. */
    fun showKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun viewRowColAt(x: Float, y: Float): Pair<Int, Int> {
        val emu = emulator
        val row = (y / charHeight).toInt().coerceAtLeast(0)
        val maxCol = ((emu?.cols ?: 1) - 1).coerceAtLeast(0)
        val col = (x / charWidth).toInt().coerceIn(0, maxCol)
        return row to col
    }

    private fun beginSelectionAtWord(x: Float, y: Float) {
        val (row, col) = viewRowColAt(x, y)
        val cells = cellsForViewRow(row) ?: return
        var start = col.coerceIn(0, cells.size - 1)
        var end = start
        if (cells[start].char != ' ') {
            while (start > 0 && cells[start - 1].char != ' ') start--
            while (end < cells.size - 1 && cells[end + 1].char != ' ') end++
        }
        selStartRow = row
        selStartCol = start
        selEndRow = row
        selEndCol = end
        selecting = true
        actionMode = startActionMode(selectionActionModeCallback, ActionMode.TYPE_FLOATING)
        invalidate()
    }

    private fun endSelection() {
        selecting = false
        actionMode?.finish()
        actionMode = null
        invalidate()
    }

    private data class SelectionRange(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

    private fun normalizedSelection(): SelectionRange {
        return if (selStartRow < selEndRow || (selStartRow == selEndRow && selStartCol <= selEndCol)) {
            SelectionRange(selStartRow, selStartCol, selEndRow, selEndCol)
        } else {
            SelectionRange(selEndRow, selEndCol, selStartRow, selStartCol)
        }
    }

    private fun extractSelectedText(): String {
        val (startRow, startCol, endRow, endCol) = normalizedSelection()
        val lines = mutableListOf<String>()
        for (viewRow in startRow..endRow) {
            val cells = cellsForViewRow(viewRow) ?: continue
            val lineStartCol = if (viewRow == startRow) startCol else 0
            val lineEndCol = (if (viewRow == endRow) endCol else cells.size - 1).coerceAtMost(cells.size - 1)
            if (lineEndCol < lineStartCol) {
                lines.add("")
                continue
            }
            val text = (lineStartCol..lineEndCol).joinToString("") { cells[it].char.toString() }
            lines.add(text.trimEnd())
        }
        return lines.joinToString("\n")
    }

    private fun copySelectionToClipboard() {
        val text = extractSelectedText()
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    /** Sends the device clipboard's text content to the remote shell, as if typed. */
    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val text = clipboard.primaryClip?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        } ?: return
        if (text.isEmpty()) return
        listener?.onInput(text.replace("\n", "\r").toByteArray(Charsets.UTF_8))
    }

    private val selectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, MENU_ID_COPY, 0, R.string.action_copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == MENU_ID_COPY) {
                copySelectionToClipboard()
                endSelection()
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selecting = false
            actionMode = null
            invalidate()
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_TEXT_VARIATION_VISIBLE_PASSWORD is the standard trick terminal apps use to
        // force keyboards out of predictive/composing mode: with plain TYPE_CLASS_TEXT,
        // many keyboards start "composing" a word after the first letter (autocorrect
        // candidate tracking) instead of committing each keystroke immediately, so only
        // the first character - sent before composing kicked in - ever reached us.
        // Password-variation fields are expected to receive raw keystrokes, so keyboards
        // skip composing/autocorrect for them entirely. We never render it as a password
        // field ourselves (we draw the terminal, not a text box), so there's no dot-masking.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            private var composingText = ""

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composingText = ""
                if (stickyCtrl && text.length == 1) {
                    stickyCtrl = false
                    val ctrlByte = KeyMapper.controlByteForChar(text[0])
                    if (ctrlByte != null) {
                        listener?.onInput(byteArrayOf(ctrlByte))
                        return true
                    }
                }
                listener?.onInput(text.toString().toByteArray(Charsets.UTF_8))
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                // Defensive fallback for keyboards that still compose despite the
                // password-variation hint above: setComposingText() replaces the whole
                // composing span each call rather than appending, so diff against what we
                // last saw and only send the actual change - otherwise every update would
                // re-send the whole (growing) word and duplicate characters on screen.
                val new = text.toString()
                when {
                    new.startsWith(composingText) -> {
                        val added = new.substring(composingText.length)
                        if (added.isNotEmpty()) listener?.onInput(added.toByteArray(Charsets.UTF_8))
                    }
                    composingText.startsWith(new) -> {
                        val removed = composingText.length - new.length
                        repeat(removed) { listener?.onInput(byteArrayOf(0x7F.toByte())) }
                    }
                    else -> {
                        repeat(composingText.length) { listener?.onInput(byteArrayOf(0x7F.toByte())) }
                        if (new.isNotEmpty()) listener?.onInput(new.toByteArray(Charsets.UTF_8))
                    }
                }
                composingText = new
                return true
            }

            override fun finishComposingText(): Boolean {
                composingText = ""
                return super.finishComposingText()
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) {
                    listener?.onInput(byteArrayOf(0x7F.toByte()))
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    // Let this go through the normal dispatch chain (BaseInputConnection's
                    // default behavior) instead of our custom key routing below - that's
                    // what makes "hide keyboard" dismiss just the keyboard instead of the
                    // synthetic BACK event falling through and closing the whole activity.
                    return super.sendKeyEvent(event)
                }
                if (event.action == KeyEvent.ACTION_DOWN) {
                    return this@TerminalView.onKeyDown(event.keyCode, event)
                }
                return true
            }
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val emu = emulator
        // Some on-screen keyboards deliver plain letters as real KeyEvents via
        // InputConnection.sendKeyEvent() rather than commitText() - a previous version
        // tried to defer those to commitText() to play nicer with autocorrect/composing
        // keyboards, but on keyboards that only ever use sendKeyEvent for letters, that
        // silently dropped every typed character. Always handle it here instead; the
        // small risk of a double-send from a keyboard that does both is far less harmful
        // than input never arriving at all.
        val ctrlActive = event.isCtrlPressed || stickyCtrl
        val bytes = KeyMapper.map(
            keyCode = keyCode,
            unicodeChar = event.unicodeChar,
            ctrl = ctrlActive,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
            applicationCursorKeys = emu?.applicationCursorKeys ?: false
        )
        if (bytes != null) {
            if (stickyCtrl) stickyCtrl = false
            listener?.onInput(bytes)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val MENU_ID_COPY = 1
    }
}
