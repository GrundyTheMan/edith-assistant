package com.edith.app.voice.handsfree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakePhraseTest {
    @Test fun `hears hey edith`() = assertEquals(2, WakePhrase.find("Hey Edith"))
    @Test fun `hears it after other words`() = assertEquals(6, WakePhrase.find("so I was saying hey Edith, what time"))
    @Test fun `punctuation and case do not matter`() = assertEquals(2, WakePhrase.find("HEY, EDITH!"))
    @Test fun `a misheard name counts when loose`() = assertEquals(2, WakePhrase.find("hey edit"))
    @Test fun `a misheard name does not count when strict`() = assertNull(WakePhrase.find("hey edit", loose = false))
    @Test fun `run together`() = assertEquals(1, WakePhrase.find("heyedith"))
    @Test fun `the name alone is not enough`() = assertNull(WakePhrase.find("Edith is my friend"))
    @Test fun `other greetings with other names do not count`() = assertNull(WakePhrase.find("hey Siri"))
    @Test fun `a taken wake word is not found twice in a growing transcript`() {
        val first = WakePhrase.find("hey edith")!!
        assertNull(WakePhrase.find("hey edith what is", after = first))
    }
    @Test fun `a second one later is found`() {
        val t = "hey edith what is the time hey edith"
        assertEquals(8, WakePhrase.find(t, after = 3))
    }
    @Test fun `empty`() = assertNull(WakePhrase.find(""))
    @Test fun `sensitivity picks strict or loose`() { assertEquals(false, WakePhrase.isLoose(0.1f)); assertEquals(true, WakePhrase.isLoose(0.5f)) }
}
