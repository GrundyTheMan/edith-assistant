package com.edith.app.chat.engine

import com.edith.app.data.chat.ChatRepository
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** D12 title rules that don't need a database or a model. */
class ConversationTitleTest {

    @Test fun `first-message titles are one line, cut at a word, with an ellipsis`() {
        assertEquals("Hi Edith", ChatRepository.firstMessageTitle("  Hi\n  Edith  "))
        assertEquals("What's the weather going to be like in…",
            ChatRepository.firstMessageTitle("What's the weather going to be like in Detroit this weekend, roughly?"))
        assertNull(ChatRepository.firstMessageTitle(" \n "))
        val noSpaces = ChatRepository.firstMessageTitle("x".repeat(100))!!
        assertEquals(ChatRepository.TITLE_MAX_CHARS, noSpaces.length)
        assertTrue(noSpaces.endsWith("…"))
    }

    @Test fun `Edith's titles lose quotes, labels, markdown and ending punctuation`() {
        assertEquals("Picking a Number", ConversationTitler.clean("\"Picking a Number.\""))
        assertEquals("Detroit Weekend Weather", ConversationTitler.clean("Title: **Detroit Weekend Weather**\n\nHope that helps!"))
        assertEquals("Houseplant Names", ConversationTitler.clean("\n\n# “Houseplant Names”"))
        assertNull(ConversationTitler.clean("  \n \"\" "))
        val long = ConversationTitler.clean("word ".repeat(30))!!
        assertTrue(long.length <= ConversationTitler.MAX_CHARS && long.endsWith("…"))
    }
}
