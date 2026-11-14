package com.focuslens.focuslens.focus

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStoreTest {
    private fun res(steps: List<String>, intent: String = "next_step") =
        HintResponse(steps.first(), JSONObject().put("title", "T").put("situation", "homework"), null, "x^2", null, steps, null, "wrong sign", intent = intent)

    @Test
    fun applyHintFillsCropQuestAndStep() {
        val store = CardStore(Files.createTempDirectory("cards").toFile())
        val id = store.newId()
        store.write(id, CardStore.newCard(id, "s", "auto", "en").put("request", JSONObject()))
        val r = res(listOf("a", "b"))
        store.update(id) { store.applyHint(id, it, r, null, byteArrayOf(1, 2), CardStore.isQuest(r, "homework"), "photo.jpg", true) }
        val c = store.read(id)!!
        assertEquals("done", c.getString("status"))
        assertEquals("crop.png", c.getString("cropImage"))
        assertNotNull(store.readFile(id, "crop.png"))
        assertEquals(2, c.getJSONObject("quest").getJSONArray("steps").length())
        assertEquals("a", c.getJSONArray("steps").getJSONObject(0).getString("text"))
        assertEquals("wrong sign", c.getString("mistake"))
        assertEquals("x^2", c.getString("formula"))
        assertFalse(c.has("request"))
    }

    @Test
    fun questOnlyWhenUserHasToAct() {
        assertTrue(CardStore.isQuest(res(listOf("a", "b")), "desk"))
        assertFalse(CardStore.isQuest(res(listOf("a", "b")), "lecture"))
        assertFalse(CardStore.isQuest(res(listOf("a", "b"), "answer"), "desk"))
        assertFalse(CardStore.isQuest(res(listOf("a")), "reading"))
    }
}
