package com.raspberryconnect.terminal.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val emu = emulator ?: return false
            val lineDelta = (distanceY / charHeight).toInt()
            if (lineDelta == 0) return false
            val maxOffset = emu.scrollback.size
            scrollOffset = (scrollOffset + lineDelta).coerceIn(0, maxOffset)
            invalidate()
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
        val history = emu.scrollback

        for (viewRow in 0 until rows) {
            val logicalIndex = viewRow - scrollOffset
            val cells: Array<TerminalCell> = when {
                logicalIndex < 0 -> {
                    val historyIndex = history.size + logicalIndex
                    if (historyIndex in history.indices) history[historyIndex] else continue
                }
                else -> emu.rowAt(logicalIndex)
            }
            drawRow(canvas, cells, viewRow, cols)
        }

        if (scrollOffset == 0 && emu.cursorVisible && hasFocus()) {
            drawCursor(canvas, emu.cursorRow, emu.cursorCol)
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
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
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

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) {
                    listener?.onInput(byteArrayOf(0x7F.toByte()))
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
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
        val isPrintableFromSoftKeyboard = !KeyMapper.isControlKey(keyCode) &&
            !event.isCtrlPressed &&
            event.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD &&
            event.unicodeChar > 0
        if (isPrintableFromSoftKeyboard) {
            // Let the IME's commitText() deliver this so composing/autocorrect keyboards work.
            return super.onKeyDown(keyCode, event)
        }

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
}
