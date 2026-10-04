package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconReplyTest {

    @Test
    fun `text envelope carries correlation and comprehension fields and omits nulls`() {
        val json = BeaconReply.text("use Qdrant", explicitItemID = "att_1", question = "Use which DB?", sessionTitle = "retrieval architecture")
            .envelopeJson(clientMessageID = "0198abcd-7f12-7abc-9abc-abcdefabcdef")

        assertTrue(json.contains("\"v\":1"))
        assertTrue(json.contains("\"clientMessageID\":\"0198abcd-7f12-7abc-9abc-abcdefabcdef\""))
        assertTrue(json.contains("\"kind\":\"text\""))
        assertTrue(json.contains("\"text\":\"use Qdrant\""))
        assertTrue(json.contains("\"explicitItemID\":\"att_1\""))
        assertTrue(json.contains("\"question\":\"Use which DB?\""))
        assertTrue(json.contains("\"sessionTitle\":\"retrieval architecture\""))
        assertTrue(json.contains("\"note\":\""))
        assertFalse(json.contains("index"))
        assertFalse(json.contains("contextTag"))
    }

    @Test
    fun `blank comprehension context is omitted, transport note always present`() {
        val json = BeaconReply.text("yes", explicitItemID = "att_1", question = "   ", sessionTitle = "")
            .envelopeJson("id-1")

        assertFalse(json.contains("\"question\""))
        assertFalse(json.contains("\"sessionTitle\""))
        assertTrue(json.contains("\"note\":\""))
    }

    @Test
    fun `choice envelope carries index without text`() {
        val json = BeaconReply(Kind_CHOICE, index = 1, explicitItemID = "att_2")
            .envelopeJson("id-1")

        assertTrue(json.contains("\"kind\":\"choice\""))
        assertTrue(json.contains("\"index\":1"))
        assertFalse(json.contains("\"text\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `text kind requires non-blank text`() {
        BeaconReply.text("   ", explicitItemID = "att_1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `choice kind requires index`() {
        BeaconReply(BeaconReply.Kind.CHOICE, explicitItemID = "att_1")
    }

    private val Kind_CHOICE get() = BeaconReply.Kind.CHOICE
}
