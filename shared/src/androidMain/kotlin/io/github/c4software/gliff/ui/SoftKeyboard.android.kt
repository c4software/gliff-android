package io.github.c4software.gliff.ui

import android.content.Context
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.c4software.gliff.client.Keys

@Composable
actual fun hiddenPointerIcon(): PointerIcon? {
    val context = LocalContext.current
    return remember(context) { PointerIcon(android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL)) }
}

/**
 * An invisible view the input method types into. It also holds the focus, so
 * a plugged keyboard's keys land here too.
 */
@Composable
actual fun SoftKeyboard(shown: Boolean, sink: KeySink) {
    val current = rememberUpdatedState(sink)
    var view by remember { mutableStateOf<KeyCatcher?>(null) }
    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { context -> KeyCatcher(context) { current.value }.also { view = it } },
    )
    LaunchedEffect(shown, view) {
        val target = view ?: return@LaunchedEffect
        val input = target.context.getSystemService(InputMethodManager::class.java)
        target.requestFocus()
        // Posted: the input method only serves a view once its focus has settled.
        target.post {
            if (shown) input.showSoftInput(target, 0) else input.hideSoftInputFromWindow(target.windowToken, 0)
        }
    }
}

private class KeyCatcher(context: Context, private val sink: () -> KeySink) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A password field that shows its text: the keyboard commits each
        // character as typed, with no suggestion, correction or composing.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return Connection()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = handle(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = handle(event) || super.onKeyUp(keyCode, event)

    private fun handle(event: KeyEvent): Boolean {
        // Back, volume and the like stay the device's own.
        if (event.isSystem) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        // A real keyboard reports the Linux code of the key itself.
        if (event.scanCode != 0 && event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD) {
            if (!down || event.repeatCount == 0) sink().raw(event.scanCode, down)
            return true
        }
        val code = SPECIAL[event.keyCode]
        if (code != null) {
            if (down) sink().key(code)
            return true
        }
        val char = event.unicodeChar
        if (char == 0) return false
        if (down) sink().text(String(Character.toChars(char)))
        return true
    }

    private inner class Connection : BaseInputConnection(this, false) {
        /** Text the keyboard is still working on, already typed on the remote. */
        private var composing = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = ""
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { sink().key(Keys.BACKSPACE) }
            repeat(afterLength) { sink().key(Keys.DELETE) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            handle(event)
            return true
        }

        /** Turn what was typed for the composing text into `text`, keeping their common start. */
        private fun replaceComposing(text: String) {
            val same = composing.commonPrefixWith(text).length
            repeat(composing.length - same) { sink().key(Keys.BACKSPACE) }
            if (text.length > same) sink().text(text.substring(same))
        }
    }

    private companion object {
        val SPECIAL = mapOf(
            KeyEvent.KEYCODE_DEL to Keys.BACKSPACE,
            KeyEvent.KEYCODE_FORWARD_DEL to Keys.DELETE,
            KeyEvent.KEYCODE_ENTER to Keys.ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER to Keys.ENTER,
            KeyEvent.KEYCODE_TAB to Keys.TAB,
            KeyEvent.KEYCODE_ESCAPE to Keys.ESC,
            KeyEvent.KEYCODE_DPAD_UP to Keys.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to Keys.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to Keys.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to Keys.RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME to Keys.HOME,
            KeyEvent.KEYCODE_MOVE_END to Keys.END,
            KeyEvent.KEYCODE_PAGE_UP to Keys.PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN to Keys.PAGE_DOWN,
            KeyEvent.KEYCODE_INSERT to Keys.INSERT,
        )
    }
}
