package io.github.c4software.gliff.android

import android.app.Activity
import android.app.ActivityOptions
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.WindowInsets
import android.view.WindowInsetsController

/** The window is a desktop one, which the system gives a header bar. */
internal val Activity.inDesktopWindow: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        (window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.captionBar())?.top ?: 0) > 0

/**
 * The header bar of a desktop window shows what the app draws under it, the
 * app's black, with light controls: the window reads as one block.
 */
internal fun Activity.blendHeaderBar() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    window.insetsController?.setSystemBarsAppearance(
        WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
        WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
            WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
    )
}

/**
 * From a desktop window, a session opens in a 16:9 window centred on the
 * display, rather than the system's default, often shaped like a phone: the
 * remote's resolution follows the window. Elsewhere, no options.
 */
internal fun Activity.sessionLaunchOptions(): Bundle? {
    if (!inDesktopWindow || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val display = windowManager.maximumWindowMetrics.bounds
    var width = display.width() * 7 / 10
    var height = width * 9 / 16
    if (height > display.height() * 8 / 10) {
        height = display.height() * 8 / 10
        width = height * 16 / 9
    }
    val left = display.left + (display.width() - width) / 2
    val top = display.top + (display.height() - height) / 2
    return ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, left + width, top + height)).toBundle()
}

/**
 * A mouse and a keyboard are plugged in: the screen's floating buttons, made
 * for a finger and an on-screen keyboard, have nothing left to do.
 */
internal fun Activity.hasMouseAndKeyboard(): Boolean {
    val config = resources.configuration
    val keyboard = config.keyboard == Configuration.KEYBOARD_QWERTY &&
        config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val inputs = getSystemService(InputManager::class.java) ?: return false
    val mouse = inputs.inputDeviceIds.any { id ->
        val device = InputDevice.getDevice(id)
        device != null && device.isExternal && device.supportsSource(InputDevice.SOURCE_MOUSE)
    }
    return keyboard && mouse
}
