package io.github.c4software.gliff.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.github.c4software.gliff.client.HidKeys
import io.github.c4software.gliff.client.Keys
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIKeyInputProtocol
import platform.UIKit.UIKeyboardAppearance
import platform.UIKit.UIKeyboardAppearanceDark
import platform.UIKit.UIKeyboardType
import platform.UIKit.UIKeyboardTypeDefault
import platform.UIKit.UIPress
import platform.UIKit.UIPressesEvent
import platform.UIKit.UITextAutocapitalizationType
import platform.UIKit.UITextAutocorrectionType
import platform.UIKit.UITextInlinePredictionType
import platform.UIKit.UITextSmartDashesType
import platform.UIKit.UITextSmartInsertDeleteType
import platform.UIKit.UITextSmartQuotesType
import platform.UIKit.UITextSpellCheckingType
import platform.UIKit.UIView

/** The pointer is hidden by the window itself on this target, see `PointerHider`. */
@Composable
actual fun hiddenPointerIcon(): PointerIcon? = null

/**
 * Two invisible views, one of which holds the keyboard focus. While `shown`
 * it is the one the on-screen keyboard types into; otherwise the one that
 * brings no keyboard up. A plugged keyboard's keys land on either.
 */
@OptIn(ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun SoftKeyboard(shown: Boolean, sink: KeySink) {
    val current = rememberUpdatedState(sink)
    var typing by remember { mutableStateOf<KeyCatcher?>(null) }
    var silent by remember { mutableStateOf<PressCatcher?>(null) }
    UIKitView(
        factory = { KeyCatcher { current.value }.also { typing = it } },
        modifier = Modifier.size(1.dp),
        properties = UIKitInteropProperties(interactionMode = null),
    )
    UIKitView(
        factory = { PressCatcher { current.value }.also { silent = it } },
        modifier = Modifier.size(1.dp),
        properties = UIKitInteropProperties(interactionMode = null),
    )
    LaunchedEffect(shown, typing, silent) {
        // The one that loses the focus first, so the other can take it.
        if (shown) {
            silent?.holdsKeys = false
            typing?.holdsKeys = true
        } else {
            typing?.holdsKeys = false
            silent?.holdsKeys = true
        }
    }
    DisposableEffect(typing, silent) {
        val text = typing
        val keys = silent
        onDispose {
            text?.holdsKeys = false
            keys?.holdsKeys = false
        }
    }
}

/**
 * The keys of a plugged keyboard among `presses`, sent as they are, down and
 * up: the remote's own layout gives them their meaning. False when none is a
 * key this app knows. Handled and not passed on, they do not come back as
 * text.
 */
private fun KeySink.forward(presses: Set<*>, pressed: Boolean): Boolean {
    var any = false
    for (press in presses) {
        val usage = (press as? UIPress)?.key?.keyCode ?: continue
        val code = HidKeys.evdev(usage.toInt()) ?: continue
        raw(code, pressed)
        any = true
    }
    return any
}

/** Holds the keyboard focus without being a text input: no on-screen keyboard comes up. */
@OptIn(ExperimentalForeignApi::class)
private class PressCatcher(private val sink: () -> KeySink) : UIView(frame = CGRectZero.readValue()) {
    /** Whether this view is to hold the focus; taken as soon as it is in a window. */
    var holdsKeys = false
        set(value) {
            field = value
            if (!value) resignFirstResponder() else if (window != null) becomeFirstResponder()
        }

    override fun canBecomeFirstResponder(): Boolean = true

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        // Asked for before the view was in a window: taken now.
        if (holdsKeys && window != null) becomeFirstResponder()
    }

    override fun pressesBegan(presses: Set<*>, withEvent: UIPressesEvent?) {
        if (!sink().forward(presses, pressed = true)) super.pressesBegan(presses, withEvent)
    }

    override fun pressesEnded(presses: Set<*>, withEvent: UIPressesEvent?) {
        if (!sink().forward(presses, pressed = false)) super.pressesEnded(presses, withEvent)
    }

    override fun pressesCancelled(presses: Set<*>, withEvent: UIPressesEvent?) {
        if (!sink().forward(presses, pressed = false)) super.pressesCancelled(presses, withEvent)
    }
}

/**
 * The view the on-screen keyboard types into: a text input, which is what
 * brings it up. Kotlin cannot yet derive one view from the other, hence the
 * same few lines twice.
 */
@OptIn(ExperimentalForeignApi::class)
private class KeyCatcher(private val sink: () -> KeySink) : UIView(frame = CGRectZero.readValue()), UIKeyInputProtocol {
    /** Whether this view is to hold the focus; taken as soon as it is in a window. */
    var holdsKeys = false
        set(value) {
            field = value
            if (!value) resignFirstResponder() else if (window != null) becomeFirstResponder()
        }

    override fun canBecomeFirstResponder(): Boolean = true

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        // Asked for before the view was in a window: taken now.
        if (holdsKeys && window != null) becomeFirstResponder()
    }

    override fun pressesBegan(presses: Set<*>, withEvent: UIPressesEvent?) {
        if (!sink().forward(presses, pressed = true)) super.pressesBegan(presses, withEvent)
    }

    override fun pressesEnded(presses: Set<*>, withEvent: UIPressesEvent?) {
        if (!sink().forward(presses, pressed = false)) super.pressesEnded(presses, withEvent)
    }

    override fun pressesCancelled(presses: Set<*>, withEvent: UIPressesEvent?) {
        if (!sink().forward(presses, pressed = false)) super.pressesCancelled(presses, withEvent)
    }

    // UIKeyInput: each character as it is typed, with nothing in between.

    /** Always: there is no telling what the remote holds, and backspace must reach it. */
    override fun hasText(): Boolean = true

    override fun insertText(text: String) = sink().text(text)

    override fun deleteBackward() = sink().key(Keys.BACKSPACE)

    // No suggestion, correction or rewriting: what is typed is what is sent.

    override fun autocorrectionType(): UITextAutocorrectionType = UITextAutocorrectionType.UITextAutocorrectionTypeNo

    override fun autocapitalizationType(): UITextAutocapitalizationType = UITextAutocapitalizationType.UITextAutocapitalizationTypeNone

    override fun spellCheckingType(): UITextSpellCheckingType = UITextSpellCheckingType.UITextSpellCheckingTypeNo

    override fun smartQuotesType(): UITextSmartQuotesType = UITextSmartQuotesType.UITextSmartQuotesTypeNo

    override fun smartDashesType(): UITextSmartDashesType = UITextSmartDashesType.UITextSmartDashesTypeNo

    override fun smartInsertDeleteType(): UITextSmartInsertDeleteType = UITextSmartInsertDeleteType.UITextSmartInsertDeleteTypeNo

    override fun inlinePredictionType(): UITextInlinePredictionType = UITextInlinePredictionType.UITextInlinePredictionTypeNo

    override fun keyboardType(): UIKeyboardType = UIKeyboardTypeDefault

    override fun keyboardAppearance(): UIKeyboardAppearance = UIKeyboardAppearanceDark
}
