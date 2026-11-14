package com.focuslens.focuslens.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationsTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun databaseIdFromUrls() {
        assertEquals(id, Integrations.parseDatabaseId(id))
        assertEquals(id, Integrations.parseDatabaseId("https://www.notion.so/My-Notes-$id?v=fedcba9876543210fedcba9876543210"))
        assertEquals(id, Integrations.parseDatabaseId("https://www.notion.so/workspace/$id"))
        assertEquals(id, Integrations.parseDatabaseId("01234567-89ab-cdef-0123-456789abcdef"))
        assertEquals(id, Integrations.parseDatabaseId("  " + id.uppercase() + " "))
        assertNull(Integrations.parseDatabaseId("https://www.notion.so/nothing-here"))
        assertNull(Integrations.parseDatabaseId(""))
    }

    @Test
    fun csvEscapesQuotesAndNewlines() {
        assertEquals("\"a\",\"b\"", Integrations.csvLine("a", "b"))
        assertEquals("\"say \"\"hi\"\"\",\"x,y\nz\"", Integrations.csvLine("say \"hi\"", "x,y\nz"))
        assertEquals("\"q\",\"a\"\r\n\"q2\",\"a2\"\r\n", Integrations.csv(listOf("q" to "a", "q2" to "a2")))
    }

    @Test
    fun blocksSplitLongTextAndSkipEmpty() {
        val c = NotionCard("Title", "x".repeat(4500), "", listOf("one", "two"), listOf("Ann: hi"), "")
        val b = Integrations.notionBlocks(listOf(c))
        assertEquals(listOf("heading_2", "paragraph", "paragraph", "paragraph", "bulleted_list_item", "bulleted_list_item", "bulleted_list_item"), b.map { it.type })
        assertTrue(b.all { it.text.length <= 2000 })
        assertEquals(4500, b.filter { it.type == "paragraph" }.sumOf { it.text.length })
    }

    @Test
    fun fixAndMistakeLabels() {
        val b = Integrations.notionBlocks(listOf(NotionCard("T", "", "use 2", emptyList(), emptyList(), "sign")))
        assertEquals(listOf("T", "Fix: use 2", "Mistake: sign"), b.map { it.text })
    }

    @Test
    fun childrenAreChunkedBy100() {
        val cards = List(150) { NotionCard("c$it", "", "", emptyList(), emptyList(), "") }
        val chunks = Integrations.notionBlocks(cards).chunked(Integrations.NOTION_MAX_CHILDREN)
        assertEquals(listOf(100, 50), chunks.map { it.size })
    }

    @Test
    fun tokenIsMasked() {
        assertEquals("•••• 6789", Integrations.mask("secret_123456789"))
        assertEquals("", Integrations.mask(""))
    }
}
