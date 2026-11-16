package com.edith.app.voice.own

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Where [SherpaTts] sends audio: an [AudioTrack] on the phone, a fake in tests. Written to by one thread, [stop]ped
 * by any.
 */
interface AudioSink {
    /** Frames handed to the speaker since the last [stop] or [release]. */
    val framesWritten: Long

    /** Frames the speaker has played of those. [framesPlayed] reaching [framesWritten] means it was all heard. */
    fun framesPlayed(): Long

    /** Counts [stop]s. Read it before starting on a sentence and pass it to [write]. */
    val generation: Int

    /**
     * Blocks until [samples] (mono floats at [sampleRate]) are accepted. False if [stop] cut it off, or came after
     * [generation] was read: audio meant for before a stop can never reach the speaker after it.
     */
    fun write(samples: FloatArray, sampleRate: Int, generation: Int): Boolean

    /** Drops everything not yet played at once, and wakes a blocked [write]. The next [write] starts afresh. */
    fun stop()

    /** Frees the audio resources (nothing is being said). */
    fun release()
}

/** The phone's speaker, with the same attributes as [com.edith.app.voice.speech.AndroidTts] (C36 §1). */
class AudioTrackSink : AudioSink {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var rate = 0
    private var written = 0L

    @Volatile override var generation: Int = 0
        private set
    override val framesWritten: Long get() = synchronized(lock) { written }

    override fun framesPlayed(): Long = synchronized(lock) {
        val t = track ?: return written
        t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    }

    override fun write(samples: FloatArray, sampleRate: Int, generation: Int): Boolean {
        val t = synchronized(lock) {
            if (generation != this.generation) return false
            val existing = track
            if (existing == null || rate != sampleRate) {
                dropTrack()?.let { discard(it) }
                val created = create(sampleRate).also { it.play() }
                track = created
                rate = sampleRate
                created
            } else existing
        }
        var offset = 0
        while (offset < samples.size) {
            val n = t.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (n < 0) return false   // stopped or released from another thread
            offset += n
            synchronized(lock) { if (track === t) written += n }
        }
        return synchronized(lock) { track === t }
    }

    override fun stop() {
        val t = synchronized(lock) {
            generation++
            dropTrack()
        } ?: return
        discard(t)
    }

    override fun release() = stop()

    /** Called with [lock] held. */
    private fun dropTrack(): AudioTrack? = track.also { track = null; written = 0L }

    private fun discard(t: AudioTrack) {
        runCatching { t.pause(); t.flush() }
        t.release()
    }

    private fun create(sampleRate: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(ATTRIBUTES)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(sampleRate * Float.SIZE_BYTES)   // one second
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    companion object {
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** Audio focus for speech: ducks others, and says when another app took the audio away. */
interface SpeechFocus {
    /** Called on any thread when focus is lost (a call, a navigation prompt that doesn't duck). */
    var onLost: () -> Unit
    fun request()
    fun abandon()
}

/** [SpeechFocus] using Android's audio focus. */
class AndroidSpeechFocus(private val audio: AudioManager) : SpeechFocus {
    override var onLost: () -> Unit = {}
    private var holding = false
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioTrackSink.ATTRIBUTES)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
        }
        .build()

    @Synchronized override fun request() {
        if (!holding) holding = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Synchronized override fun abandon() {
        if (holding) audio.abandonAudioFocusRequest(request)
        holding = false
    }
}
