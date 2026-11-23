package com.edith.app.tools.framework

/** A JPEG a tool brought back, and the file name it's kept under. */
class ToolImage(val fileName: String, val jpeg: ByteArray) {
    override fun toString() = "ToolImage($fileName, ${jpeg.size} bytes)"
}
