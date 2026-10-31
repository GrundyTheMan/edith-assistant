package com.edith.app.data

import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.AttachmentDirection
import com.edith.app.data.db.EnumConverters
import com.edith.app.data.db.ListConverters
import com.edith.app.data.db.MessageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** C05 rules that don't need a database (the Room behaviour is in androidTest ChatRepositoryTest). */
class StorageRulesTest {
    @Test fun `tool-call text is stripped, ordinary text is untouched`() {
        assertEquals("Sure. Done", ChatRepository.stripToolCalls("Sure.<tool_call>{\"name\":\"x\"}</tool_call> Done"))
        assertEquals("Done", ChatRepository.stripToolCalls("<tool_call>\n{\"a\":1}\n</tool_call>\nDone"))
        assertEquals("A  B", ChatRepository.stripToolCalls("A  B"))   // double space kept: nothing to strip
        assertEquals("1 2", ChatRepository.stripToolCalls("1 <tool_call>a</tool_call> <tool_call>b</tool_call> 2"))
    }

    @Test fun `enum strings match the SQL in 01-architecture and round-trip`() {
        assertEquals("voice_memo", EnumConverters.fromMessageSource(MessageSource.VoiceMemo))
        assertEquals("hands_free", EnumConverters.fromMessageSource(MessageSource.HandsFree))
        assertEquals("from_edith", EnumConverters.fromAttachmentDirection(AttachmentDirection.FromEdith))
        MessageSource.entries.forEach { assertEquals(it, EnumConverters.toMessageSource(EnumConverters.fromMessageSource(it))) }
        AttachmentDirection.entries.forEach {
            assertEquals(it, EnumConverters.toAttachmentDirection(EnumConverters.fromAttachmentDirection(it)))
        }
    }

    @Test fun `tools_used is a JSON array and bad data reads as empty`() {
        assertEquals("[\"get_weather\",\"web_search\"]", ListConverters.toStringList(listOf("get_weather", "web_search")))
        assertEquals(listOf("get_weather"), ListConverters.fromStringList("[\"get_weather\"]"))
        assertEquals(emptyList<String>(), ListConverters.fromStringList("not json"))
    }

    @Test fun `schema version 1 is exported to app schemas`() {
        // Unit tests run with the app module as the working directory.
        val schema = File("schemas/com.edith.app.data.db.EdithDatabase/1.json")
        assertTrue("missing ${schema.absolutePath}", schema.isFile)
        val json = schema.readText()
        listOf("conversations", "messages", "attachments").forEach {
            assertTrue("table $it not in the schema", json.contains("\"tableName\": \"$it\""))
        }
    }
}
