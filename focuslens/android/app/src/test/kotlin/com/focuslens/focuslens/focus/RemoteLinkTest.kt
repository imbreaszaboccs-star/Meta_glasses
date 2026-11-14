package com.focuslens.focuslens.focus

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLinkTest {
    @Test
    fun parseCommand() {
        val c = RemoteProto.parse("""{"id":"abc","type":"start_focus","args":{"mode":"lecture"}}""")!!
        assertEquals("abc", c.id)
        assertEquals("start_focus", c.type)
        assertEquals("lecture", c.args.getString("mode"))
        assertNotNull(RemoteProto.parse("""{"id":"abc","type":"help"}""")) // args optional
        assertNull(RemoteProto.parse("not json"))
        assertNull(RemoteProto.parse("""{"type":"help"}"""))
    }

    private fun v(type: String, args: String = "{}") = RemoteProto.validate(type, JSONObject(args))

    @Test
    fun validateStartFocus() {
        assertNull(v("start_focus"))
        assertNull(v("start_focus", """{"mode":"desk","goal":"read ch. 3"}"""))
        assertEquals("bad mode", v("start_focus", """{"mode":"party"}"""))
        assertEquals("bad goal", v("start_focus", """{"goal":5}"""))
    }

    @Test
    fun validateSetConfig() {
        assertEquals("nothing to change", v("set_config"))
        assertNull(v("set_config", """{"nudges":false,"mode":"auto"}"""))
        assertEquals("bad flag", v("set_config", """{"wakeWord":"yes"}"""))
        assertEquals("bad mode", v("set_config", """{"mode":"x"}"""))
        assertEquals(mapOf<String, Any>("readAloud" to true, "language" to "de"), RemoteProto.configPatch(JSONObject("""{"readAloud":true,"language":"de","foo":1}""")))
    }

    @Test
    fun validateGesture() {
        assertEquals(null, v("gesture", """{"g":"select"}"""))
        assertEquals(null, v("gesture", """{"g":"back"}"""))
        assertEquals("bad gesture", v("gesture", """{"g":"capture"}"""))
        assertEquals("bad gesture", v("gesture"))
    }

    @Test
    fun replyFlagAndConnectorCommands() {
        val c = RemoteProto.parse("""{"id":"abc","type":"add_note","args":{"text":"buy milk"},"reply":true}""")!!
        assertTrue(c.reply)
        assertEquals("buy milk", c.args.getString("text"))
        assertTrue(!RemoteProto.parse("""{"id":"abc","type":"help"}""")!!.reply)
        assertTrue(!RemoteProto.parse("""{"id":"abc","type":"help","reply":"yes"}""")!!.reply)
        assertNull(v("add_note", """{"text":"buy milk"}"""))
        assertNull(v("add_note", """{"text":"${"x".repeat(300)}"}"""))
        assertEquals("bad text", v("add_note", """{"text":"${"x".repeat(301)}"}"""))
        assertEquals("bad text", v("add_note", """{"text":""}"""))
        assertEquals("bad text", v("add_note", """{"text":5}"""))
        assertEquals("bad text", v("add_note"))
        assertNull(v("last_hint"))
    }

    @Test
    fun validateContractV2Commands() {
        for (t in listOf("next", "bookmark", "reconnect", "inbox_sync")) assertNull(t, v(t))
        assertNull(v("card_reviewed", """{"id":"a","reviewed":true}"""))
        assertEquals("bad reviewed", v("card_reviewed", """{"id":"a","reviewed":1}"""))
        assertEquals("bad id", v("card_reviewed", """{"reviewed":true}"""))
        assertNull(v("card_delete", """{"id":"a"}"""))
        assertEquals("bad id", v("card_delete"))
        assertNull(v("imu_label", """{"label":"none"}"""))
        assertNull(v("imu_label", """{"label":"looking_around"}"""))
        assertEquals("bad label", v("imu_label", """{"label":"dancing"}"""))
        assertEquals("bad label", v("imu_label"))
        assertNull(v("web_seen", """{"visible":false}"""))
        assertEquals("bad visible", v("web_seen", """{"visible":"yes"}"""))
    }

    @Test
    fun validateExtendedConfig() {
        assertNull(v("set_config", """{"language":"cs","bufferSec":120,"hudTimeoutSec":0,"keepAudio":"all","audioSource":"glasses","retentionDays":7}"""))
        assertNull(v("set_config", """{"bufferSec":150.0,"retentionDays":30.0}""")) // JSON numbers may be doubles
        assertNull(v("set_config", """{"dozeSound":true,"collect":false}"""))
        assertEquals("bad value", v("set_config", """{"language":"fr"}"""))
        assertEquals("bad value", v("set_config", """{"bufferSec":90}"""))
        assertEquals("bad value", v("set_config", """{"bufferSec":120.5}"""))
        assertEquals("bad value", v("set_config", """{"hudTimeoutSec":30}"""))
        assertEquals("bad value", v("set_config", """{"retentionDays":14}"""))
        assertEquals("bad value", v("set_config", """{"keepAudio":"some"}"""))
        assertEquals("bad value", v("set_config", """{"audioSource":"cloud"}"""))
        assertEquals("bad flag", v("set_config", """{"collect":"yes"}"""))
        assertEquals("nothing to change", v("set_config", """{"foo":1}"""))
        val patch = RemoteProto.configPatch(JSONObject("""{"bufferSec":150.0,"language":"de","dozeSound":true,"foo":1}"""))
        assertEquals(mapOf("bufferSec" to 150, "language" to "de", "dozeSound" to true), patch)
        assertTrue(patch["bufferSec"] is Int)
    }

    @Test
    fun validateInboxAndUnknown() {
        assertNull(v("inbox_done", """{"id":"a","done":true}"""))
        assertEquals("bad id", v("inbox_done", """{"done":true}"""))
        assertEquals("bad done", v("inbox_done", """{"id":"a","done":"true"}"""))
        assertNull(v("inbox_delete", """{"id":"a"}"""))
        assertEquals("bad id", v("inbox_delete", """{"id":""}"""))
        for (t in listOf("stop_focus", "help", "pause_mic", "resume_mic", "review_load", "refresh")) assertNull(t, v(t))
        assertEquals("unknown command", v("format_disk"))
    }

    private fun big(n: Int, len: Int) = JSONArray().also { a -> repeat(n) { a.put(JSONObject().put("t", "x".repeat(len)).put("n", it)) } }

    @Test
    fun trimStateKeepsEverythingThatFits() {
        val st = JSONObject().put("focus", JSONObject().put("lastHint", "hi")).put("inbox", big(3, 10)).put("cards", big(3, 10))
            .put("review", JSONObject().put("cards", big(3, 10)))
        val before = st.toString()
        assertEquals(before, RemoteProto.trimState(st).toString())
    }

    @Test
    fun trimStateDropsReviewFirstThenCardsThenInbox() {
        val st = JSONObject().put("focus", JSONObject().put("lastHint", "hi")).put("inbox", big(20, 1000))
            .put("cards", big(10, 6000)).put("review", JSONObject().put("cards", big(20, 1000)))
        val out = RemoteProto.trimState(st)
        assertTrue(out.toString().toByteArray().size <= RemoteProto.MAX_STATE_BYTES)
        assertEquals(0, out.getJSONObject("review").getJSONArray("cards").length())
        assertEquals(20, out.getJSONArray("inbox").length()) // inbox is the last to go
        assertTrue(out.getJSONArray("cards").length() < 10)
        // newest rows survive: the tail is dropped
        assertEquals(0, out.getJSONArray("cards").getJSONObject(0).getInt("n"))
    }

    @Test
    fun trimStateCountsUtf8Bytes() {
        val st = JSONObject().put("focus", JSONObject()).put("inbox", JSONArray()).put("cards", big(40, 0))
        st.getJSONArray("cards").let { a -> repeat(40) { a.put(JSONObject().put("t", "é".repeat(1000))) } }
        assertTrue(RemoteProto.trimState(st).toString().toByteArray(Charsets.UTF_8).size <= RemoteProto.MAX_STATE_BYTES)
    }

    @Test
    fun clipShortensWithEllipsis() {
        assertEquals("abc", RemoteProto.clip("abc", 5))
        assertEquals("abc…", RemoteProto.clip("abcdefg", 4))
        assertEquals("", RemoteProto.clip(null, 4))
    }
}
