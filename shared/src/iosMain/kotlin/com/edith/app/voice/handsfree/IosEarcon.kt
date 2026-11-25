package com.edith.app.voice.handsfree

import kotlinx.coroutines.delay
import platform.AudioToolbox.AudioServicesPlaySystemSound

/** The short sound that tells the owner Edith is listening (the system's "begin recording" tone), played before the recognizer opens the microphone. */
suspend fun iosEarcon() {
    AudioServicesPlaySystemSound(1113u)
    delay(250)   // let it finish before the microphone opens
}
