package io.github.c4software.gliff.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.c4software.gliff.client.VideoDecoder

/**
 * Not built yet. The iOS port puts an `AVSampleBufferDisplayLayer` here and
 * feeds it from a `VideoDecoder` made on VideoToolbox.
 */
@Composable
actual fun VideoSurface(decoder: VideoDecoder, onReady: () -> Unit, modifier: Modifier) {
    Box(modifier.background(Color.Black))
}
