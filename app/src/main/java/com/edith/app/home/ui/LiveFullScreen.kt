package com.edith.app.home.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.edith.app.home.StreamLiveFeed
import com.edith.app.home.TrackLiveFeed

/** How far a live picture is zoomed: [scale] (1 = whole picture) and where it is moved to ([x], [y], in pixels from the centre). */
internal data class ZoomState(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f)

/** The maths of pinch zoom (C32). Pure, so it is tested without a screen. */
internal object LiveZoom {
    const val MAX = 5f
    const val DOUBLE_TAP = 2.5f

    /**
     * Zooms by [factor] around the fingers' centre ([cx], [cy], in pixels from the top left) and moves by [panX], [panY], on a
     * view [w] by [h] pixels. The picture never goes smaller than the view and never leaves a gap at its edges.
     */
    fun apply(s: ZoomState, cx: Float, cy: Float, panX: Float, panY: Float, factor: Float, w: Float, h: Float): ZoomState {
        val scale = (s.scale * factor).coerceIn(1f, MAX)
        val ratio = scale / s.scale
        val fromCentreX = cx - w / 2
        val fromCentreY = cy - h / 2
        val limitX = w * (scale - 1f) / 2
        val limitY = h * (scale - 1f) / 2
        return ZoomState(
            scale,
            ((s.x - fromCentreX) * ratio + fromCentreX + panX).coerceIn(-limitX, limitX),
            ((s.y - fromCentreY) * ratio + fromCentreY + panY).coerceIn(-limitY, limitY),
        )
    }
}

/**
 * A live view filling the whole screen with the system bars hidden (a swipe from the edge shows them for a moment). Pinch to
 * zoom, drag to move, double tap to zoom in or back out, tap to show or hide the buttons. [onExit] goes back to the small
 * dialog (the back gesture does too); [onClose] ends the view.
 */
@Composable
internal fun LiveFullScreen(live: LiveUi, onExit: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
                }
                WindowInsetsControllerCompat(window, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        var zoom by remember { mutableStateOf(ZoomState()) }
        var controls by remember { mutableStateOf(true) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.scale; scaleY = zoom.scale; translationX = zoom.x; translationY = zoom.y }) {
                when (val feed = live.feed) {
                    is StreamLiveFeed -> LivePlayer(feed, Modifier.fillMaxSize())
                    is TrackLiveFeed -> RtcPlayer(feed, Modifier.fillMaxSize())
                    else -> Unit
                }
            }
            // A clear layer over the picture takes the touches, so the player underneath never has to.
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, factor, _ ->
                            zoom = LiveZoom.apply(zoom, centroid.x, centroid.y, pan.x, pan.y, factor, size.width.toFloat(), size.height.toFloat())
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { at ->
                                zoom = if (zoom.scale > 1f) ZoomState()
                                else LiveZoom.apply(zoom, at.x, at.y, 0f, 0f, LiveZoom.DOUBLE_TAP, size.width.toFloat(), size.height.toFloat())
                            },
                            onTap = { controls = !controls },
                        )
                    },
            )
            if (controls) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${live.name} — live", color = Color.White, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    // White on the dark bar: the theme's purple is hard to read there.
                    val onDark = ButtonDefaults.textButtonColors(contentColor = Color.White)
                    TextButton(onClick = onExit, colors = onDark) { Text("Exit full screen") }
                    TextButton(onClick = onClose, colors = onDark) { Text("Close") }
                }
            }
        }
    }
}
