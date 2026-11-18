package com.edith.app.home.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C32: the pinch zoom of the full-screen live view. */
class LiveZoomTest {
    private val w = 1000f
    private val h = 500f

    @Test fun `zooming in around the middle keeps the picture centred`() {
        val z = LiveZoom.apply(ZoomState(), 500f, 250f, 0f, 0f, 2f, w, h)
        assertEquals(2f, z.scale, 0.001f); assertEquals(0f, z.x, 0.001f); assertEquals(0f, z.y, 0.001f)
    }

    @Test fun `the point under the fingers stays under them`() {
        // Zooming 2x around (800, 100): that point of the picture must still be at (800, 100) on screen.
        val z = LiveZoom.apply(ZoomState(), 800f, 100f, 0f, 0f, 2f, w, h)
        val screenX = (800f - w / 2) * z.scale + z.x + w / 2
        val screenY = (100f - h / 2) * z.scale + z.y + h / 2
        assertEquals(800f, screenX, 0.5f); assertEquals(100f, screenY, 0.5f)
    }

    @Test fun `the scale stays between whole picture and the maximum`() {
        assertEquals(LiveZoom.MAX, LiveZoom.apply(ZoomState(), 500f, 250f, 0f, 0f, 100f, w, h).scale, 0.001f)
        assertEquals(1f, LiveZoom.apply(ZoomState(2f), 500f, 250f, 0f, 0f, 0.01f, w, h).scale, 0.001f)
    }

    @Test fun `zoomed out all the way the picture is back in the middle`() {
        val moved = LiveZoom.apply(ZoomState(), 900f, 50f, 0f, 0f, 3f, w, h)
        assertTrue(moved.x != 0f || moved.y != 0f)
        val back = LiveZoom.apply(moved, 500f, 250f, 0f, 0f, 0.01f, w, h)
        assertEquals(1f, back.scale, 0.001f); assertEquals(0f, back.x, 0.001f); assertEquals(0f, back.y, 0.001f)
    }

    @Test fun `dragging never leaves a gap at the edges`() {
        val z = LiveZoom.apply(ZoomState(2f), 500f, 250f, 5_000f, -5_000f, 1f, w, h)
        assertEquals(w / 2, z.x, 0.001f)   // 2x: half a view of spare picture each side
        assertEquals(-h / 2, z.y, 0.001f)
    }

    @Test fun `dragging a picture that is not zoomed does nothing`() {
        val z = LiveZoom.apply(ZoomState(), 500f, 250f, 300f, 300f, 1f, w, h)
        assertEquals(1f, z.scale, 0.001f); assertEquals(0f, z.x, 0.001f); assertEquals(0f, z.y, 0.001f)
    }
}
