@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.attachments

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_EndOfStream
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterOutputStatus_Error
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.Foundation.NSURL

/**
 * Converts any audio file the system can decode (an iPhone voice memo's AAC in an `.m4a`, for one) to what Ollama's audio models take: 16-bit mono WAV
 * at 16 kHz, the same as the recorder writes. Uses `AVAudioFile` to read and `AVAudioConverter` to resample and mix down. Returns the length in
 * milliseconds, or null when the file cannot be decoded or written. Written without a Mac to try it on: CI compiles it; the owner's phone is the first real run.
 */
object AudioToWav {
    private const val SAMPLE_RATE = 16_000.0
    private const val FRAMES = 4096u

    fun convert(sourcePath: String, targetPath: String): Long? {
        val input = runCatching { AVAudioFile(forReading = NSURL.fileURLWithPath(sourcePath), error = null) }.getOrNull() ?: return null
        val inFormat = input.processingFormat
        val outFormat = AVAudioFormat(commonFormat = AVAudioPCMFormatInt16, sampleRate = SAMPLE_RATE, channels = 1u, interleaved = true) ?: return null
        val converter = AVAudioConverter(fromFormat = inFormat, toFormat = outFormat) ?: return null
        val output = runCatching {
            AVAudioFile(forWriting = NSURL.fileURLWithPath(targetPath), settings = outFormat.settings, commonFormat = AVAudioPCMFormatInt16, interleaved = true, error = null)
        }.getOrNull() ?: return null
        val inBuffer = AVAudioPCMBuffer(pCMFormat = inFormat, frameCapacity = FRAMES) ?: return null
        val ratio = SAMPLE_RATE / inFormat.sampleRate
        val outCapacity = (FRAMES.toDouble() * ratio).toUInt() + 1024u
        val outBuffer = AVAudioPCMBuffer(pCMFormat = outFormat, frameCapacity = outCapacity) ?: return null
        var written = 0L
        var finished = false
        while (!finished) {
            inBuffer.frameLength = 0u
            val ok = runCatching { input.readIntoBuffer(inBuffer, error = null) }.getOrDefault(false)
            val atEnd = !ok || inBuffer.frameLength == 0u
            var supplied = false
            outBuffer.frameLength = 0u
            val status = converter.convertToBuffer(outBuffer, error = null) { _, inputStatus ->
                if (atEnd || supplied) {
                    inputStatus?.pointed?.value = AVAudioConverterInputStatus_EndOfStream
                    null
                } else {
                    supplied = true
                    inputStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
                    inBuffer
                }
            }
            if (status == AVAudioConverterOutputStatus_Error) return null
            if (outBuffer.frameLength > 0u) {
                if (!runCatching { output.writeFromBuffer(outBuffer, error = null) }.getOrDefault(false)) return null
                written += outBuffer.frameLength.toLong()
            }
            if (atEnd) finished = true
        }
        return written * 1000 / SAMPLE_RATE.toLong()
    }
}
