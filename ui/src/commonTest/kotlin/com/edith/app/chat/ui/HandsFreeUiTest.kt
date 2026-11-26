package com.edith.app.chat.ui

import com.edith.app.voice.handsfree.HandsFree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandsFreeUiTest {
    @Test fun `spoken wake word plus Talk says both`() {
        val ui = ChatViewModel.handsFreeUi(HandsFree.State.Listening, "Hey Edith", tap = true, spoken = true)
        assertEquals("Say “Hey Edith” or tap Talk", ui.status)
        assertTrue(ui.canTalk)
    }

    @Test fun `tap only keeps its words`() {
        assertEquals("Tap Talk, then speak", ChatViewModel.handsFreeUi(HandsFree.State.Listening, "Talk", tap = true).status)
    }
}
