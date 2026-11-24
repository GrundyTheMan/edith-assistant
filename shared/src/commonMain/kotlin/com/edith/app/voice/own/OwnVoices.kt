package com.edith.app.voice.own

/**
 * Edith's own voice (C36, decision D21): a Piper voice that sherpa-onnx runs on the phone. Everything about the file
 * is pinned here: it is the owner's choice by ear (2026-11-14), and the download is checked against [sha256] before
 * anything in it is used.
 */
data class OwnVoice(
    /** Folder name under `filesDir/voices/`. */
    val id: String,
    val name: String,
    /** The `.tar.bz2` in the sherpa-onnx `tts-models` GitHub release. */
    val url: String,
    val bytes: Long,
    /** Lower-case hex SHA-256 of the whole file. */
    val sha256: String,
    /** libritts_r holds 904 speakers; Edith uses this one. */
    val speakerId: Int,
) {
    /** "82 MB" for the download button. */
    val sizeLabel: String get() = "${(bytes + 500_000) / 1_000_000} MB"

    companion object {
        const val MODEL = "model.onnx"
        const val TOKENS = "tokens.txt"
        const val ESPEAK_DATA = "espeak-ng-data"
        const val MODEL_CARD = "MODEL_CARD"
    }
}

/** The voices Edith can carry (C36, D21). */
object OwnVoices {
    /**
     * `en_US-libritts_r-medium`, speaker 0: 22,050 Hz, 82,038,311 bytes (the SHA-256 is also the digest GitHub shows
     * for the release asset). Licences: see C36 "As built".
     */
    val default = OwnVoice(
        id = "libritts_r-medium-0",
        name = "Edith's own voice",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-libritts_r-medium.tar.bz2",
        bytes = 82_038_311L,
        sha256 = "10dc268f3e371696d721486123e2705a9fc1faa113491979fde4d88dba1f1b1c",
        speakerId = 0,
    )
}
