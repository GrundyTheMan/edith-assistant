package com.edith.app.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date

/** The Demo home doorbell's picture (C25 §9): a drawn porch with the time, so each snapshot is visibly new. */
object DemoPicture {
    fun jpeg(): ByteArray {
        val w = 640
        val h = 480
        val bitmap = createBitmap(w, h)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.rgb(120, 160, 200))                         // sky
        p.color = Color.rgb(90, 120, 80); c.drawRect(0f, h * 0.7f, w.toFloat(), h.toFloat(), p)   // lawn
        p.color = Color.rgb(150, 110, 80); c.drawRect(w * 0.35f, h * 0.25f, w * 0.65f, h * 0.7f, p) // door
        p.color = Color.rgb(230, 200, 90); c.drawCircle(w * 0.6f, h * 0.5f, 8f, p)              // handle
        p.color = Color.WHITE; p.textSize = 28f
        c.drawText("Demo doorbell · " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date()), 20f, 40f, p)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
