package com.edith.app.voice.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C13: the speech-cleaning regexes on Android's own regex engine, which differs from the JVM's (L25). The full
 * behaviour is covered by the JVM's SpeechOutputTest; this checks every pattern compiles and works here too.
 */
@RunWith(AndroidJUnit4::class)
class SpeechTextDeviceTest {
    @Test fun cleaningWorksOnAndroidsRegexEngine() {
        assertEquals(
            "Weather: sunny, see the forecast at a link. Thumbs up Pack an umbrella Done.",
            SpeechText.clean(
                "## **Weather**: _sunny_, see [the forecast](https://open-meteo.com) at www.example.com. " +
                    "Thumbs 👍🏽 up\n- Pack an umbrella\n> Done. <file name=\"a.txt\">x</file>",
            ),
        )
    }

    @Test fun sentencesSplitTheSameOnTheDevice() {
        val c = SentenceChunker()
        val out = c.add("Dr. Smith says 3.5 degrees. Bring a coat! <file name=\"x\">skip.</file> Bye now.") + c.flush()
        assertEquals(listOf("Dr. Smith says 3.5 degrees.", "Bring a coat!", "Bye now."), out)
    }
}
