package io.github.c4software.gliff.ios

import platform.UIKit.UIPointerInteraction
import platform.UIKit.UIPointerInteractionDelegateProtocol
import platform.UIKit.UIPointerRegion
import platform.UIKit.UIPointerStyle
import platform.darwin.NSObject

/** Gives a window's mouse pointer no shape while `hidden` says so. */
internal class PointerHider(private val hidden: () -> Boolean) : NSObject(), UIPointerInteractionDelegateProtocol {
    override fun pointerInteraction(interaction: UIPointerInteraction, styleForRegion: UIPointerRegion): UIPointerStyle? =
        if (hidden()) UIPointerStyle.hiddenPointerStyle() else null
}
