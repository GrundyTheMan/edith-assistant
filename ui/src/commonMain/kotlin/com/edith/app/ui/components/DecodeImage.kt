package com.edith.app.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes an encoded picture (JPEG, PNG) to a bitmap Compose can draw, or null when the bytes are no picture. Android: `BitmapFactory`; the desktop and the iPhone: Skia. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
