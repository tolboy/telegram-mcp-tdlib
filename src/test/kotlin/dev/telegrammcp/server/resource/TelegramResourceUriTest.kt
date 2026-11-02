package dev.telegrammcp.server.resource

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelegramResourceUriTest {
    @Test fun `canonical URIs round-trip`() {
        listOf(
            "telegram://default/chats",
            "telegram://work_2/chat/-1001234567890",
            "telegram://default/chat/42/message/1048576",
        ).forEach { assertEquals(it, TelegramResourceUri.parse(it).toString()) }
        assertEquals(TelegramResourceUri.Message("default", -100L, 7L), TelegramResourceUri.parse("telegram://default/chat/-100/message/7"))
    }

    @Test fun `backing tools follow the resource kind`() {
        assertEquals("list_chats", TelegramResourceUri.parse("telegram://a/chats")!!.backingTool)
        assertEquals("conversation_bundle", TelegramResourceUri.parse("telegram://a/chat/1")!!.backingTool)
        assertEquals("get_messages", TelegramResourceUri.parse("telegram://a/chat/1/message/2")!!.backingTool)
        assertNull(TelegramResourceUri.parse("telegram://a/chats")!!.chatId)
    }

    @Test fun `non-canonical and hostile spellings are rejected`() {
        listOf(
            null, "", "telegram://Default/chats", "telegram://default/chats/", "telegram://default/chat/042",
            "telegram://default/chat/+42", "telegram://default/chat/@durov", "telegram://default/chat/0",
            "telegram://default/chat/42/message/0", "telegram://default/chat/42/message/-1",
            "telegram://default/chat/42?x=1", "telegram://default/chat/42#m", "telegram://../chats",
            "telegram://default/chat/%34%32", "TELEGRAM://default/chats", "telegram:///chats",
            "telegram://default/chat/99999999999999999999", "telegram://default/chat/42/message/7/extra",
            "telegram://" + "a".repeat(65) + "/chats", "telegram://default/chats\n",
        ).forEach { assertNull(TelegramResourceUri.parse(it), "accepted $it") }
    }
}
