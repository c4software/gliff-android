package io.github.c4software.gliff.ios

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerContentSource
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.AVKit.AVPictureInPictureSampleBufferPlaybackDelegateProtocol
import platform.AVKit.create
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeRange
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.CMVideoDimensions
import platform.CoreMedia.kCMTimeNegativeInfinity
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.Foundation.NSError
import platform.Foundation.NSLog
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

/**
 * Picture in Picture for a session's video: leaving for another app keeps the
 * remote screen in a corner, as on Android. The system only grants it to an
 * app that plays, so the audio session is set for playback, mixed with
 * whatever else is playing since there is no sound here to protect; the
 * host's Info.plist carries the matching background mode.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal class PictureInPicture(layer: AVSampleBufferDisplayLayer, private val onChange: (Boolean) -> Unit) {
    private val playback = Playback()
    private val events = Events()
    private val controller: AVPictureInPictureController? =
        if (!AVPictureInPictureController.isPictureInPictureSupported()) {
            null
        } else {
            AVAudioSession.sharedInstance().setCategory(
                AVAudioSessionCategoryPlayback, AVAudioSessionModeMoviePlayback, AVAudioSessionCategoryOptionMixWithOthers, null,
            )
            AVPictureInPictureController(
                contentSource = AVPictureInPictureControllerContentSource.create(sampleBufferDisplayLayer = layer, playbackDelegate = playback),
            ).also {
                it.delegate = events
                // Leaving the app is what starts it: there is no button for it.
                it.canStartPictureInPictureAutomaticallyFromInline = true
                // Nothing to skip or pause in a live screen.
                it.requiresLinearPlayback = true
            }
        }

    fun close() {
        controller?.stopPictureInPicture()
        controller?.delegate = null
    }

    private inner class Events : NSObject(), AVPictureInPictureControllerDelegateProtocol {
        override fun pictureInPictureControllerWillStartPictureInPicture(pictureInPictureController: AVPictureInPictureController) =
            onChange(true)

        override fun pictureInPictureControllerDidStopPictureInPicture(pictureInPictureController: AVPictureInPictureController) =
            onChange(false)

        override fun pictureInPictureController(
            pictureInPictureController: AVPictureInPictureController,
            failedToStartPictureInPictureWithError: NSError,
        ) {
            NSLog("gliff: no picture in picture: %@", failedToStartPictureInPictureWithError.localizedDescription)
            onChange(false)
        }
    }

    /** A live stream: always playing, with no beginning and no end. */
    private class Playback : NSObject(), AVPictureInPictureSampleBufferPlaybackDelegateProtocol {
        override fun pictureInPictureController(pictureInPictureController: AVPictureInPictureController, setPlaying: Boolean) {}

        override fun pictureInPictureControllerTimeRangeForPlayback(
            pictureInPictureController: AVPictureInPictureController,
        ): CValue<CMTimeRange> = CMTimeRangeMake(kCMTimeNegativeInfinity.readValue(), kCMTimePositiveInfinity.readValue())

        override fun pictureInPictureControllerIsPlaybackPaused(pictureInPictureController: AVPictureInPictureController): Boolean =
            false

        override fun pictureInPictureController(
            pictureInPictureController: AVPictureInPictureController,
            didTransitionToRenderSize: CValue<CMVideoDimensions>,
        ) {}

        override fun pictureInPictureController(
            pictureInPictureController: AVPictureInPictureController,
            skipByInterval: CValue<CMTime>,
            completionHandler: () -> Unit,
        ) = completionHandler()
    }
}
