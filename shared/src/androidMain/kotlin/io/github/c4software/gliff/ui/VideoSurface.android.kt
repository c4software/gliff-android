package io.github.c4software.gliff.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.c4software.gliff.android.MediaCodecDecoder
import io.github.c4software.gliff.client.VideoDecoder

/**
 * A `SurfaceView` the codec renders into directly. It stretches each decoded
 * frame to its bounds, so the layout alone decides the picture's size, and it
 * keeps showing the last frame while the codec is being replaced.
 */
@Composable
actual fun VideoSurface(decoder: VideoDecoder, onReady: () -> Unit, modifier: Modifier) {
    val ready = rememberUpdatedState(onReady)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        (decoder as? MediaCodecDecoder)?.setSurface(holder.surface)
                        ready.value()
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        (decoder as? MediaCodecDecoder)?.setSurface(null)
                    }
                })
            }
        },
    )
}
