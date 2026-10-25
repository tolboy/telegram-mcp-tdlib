package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.BotCommand
import dev.telegrammcp.server.model.BotCommandScope
import dev.telegrammcp.server.model.BotCommandScopeKind
import dev.telegrammcp.server.model.ChatAdministratorRights
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TDLib 1.8.68 request shapes: voice notes, stickers, bot commands and admin rights. */
class TdLibRequestBuildersTest {

    @Test
    fun `voice note wraps file duration and waveform in InputVoiceNote`() {
        val content = TdLibRequestBuilders.voiceNoteContent("/tmp/voice.ogg", -5, "hello")

        val file = assertIs<TdApi.InputFileLocal>(content.voiceNote.voiceNote)
        assertEquals("/tmp/voice.ogg", file.path)
        assertEquals(0, content.voiceNote.duration, "negative durations are clamped")
        assertContentEquals(ByteArray(0), content.voiceNote.waveform)
        assertEquals("hello", content.caption.text)
        assertNull(TdLibRequestBuilders.voiceNoteContent("/tmp/v.ogg", 7, null).caption)
        assertEquals(7, TdLibRequestBuilders.voiceNoteContent("/tmp/v.ogg", 7, null).voiceNote.duration)
    }

    @Test
    fun `sticker wraps file in InputSticker and keeps emoji`() {
        val content = TdLibRequestBuilders.stickerContent("/tmp/s.webp", "🔥")

        val file = assertIs<TdApi.InputFileLocal>(content.sticker.sticker)
        assertEquals("/tmp/s.webp", file.path)
        assertNull(content.sticker.thumbnail)
        assertEquals(0, content.sticker.width)
        assertEquals(0, content.sticker.height)
        assertEquals("🔥", content.emoji)
        assertEquals("", TdLibRequestBuilders.stickerContent("/tmp/s.webp", null).emoji)
    }

    @Test
    fun `bot commands are never ephemeral`() {
        val command = TdLibRequestBuilders.botCommand(BotCommand("start", "Start the bot"))

        assertEquals("start", command.command)
        assertEquals("Start the bot", command.description)
        assertFalse(command.isEphemeral)
    }

    @Test
    fun `administrator rights round-trip every flag including welcome messages`() {
        val all = ChatAdministratorRights(
            canManageChat = true, canChangeInfo = true, canPostMessages = true, canEditMessages = true,
            canDeleteMessages = true, canInviteUsers = true, canRestrictMembers = true, canPinMessages = true,
            canManageTopics = true, canPromoteMembers = true, canManageVideoChats = true, canPostStories = true,
            canEditStories = true, canDeleteStories = true, canManageDirectMessages = true, canManageTags = true,
            canSendWelcomeMessages = true, isAnonymous = true,
        )
        val td = TdLibRequestBuilders.administratorRights(all)
        assertTrue(td.canSendWelcomeMessages)
        assertTrue(td.isAnonymous)
        assertEquals(all, TdLibRequestBuilders.administratorRights(td))

        // Reflect over TDLib's own class so a right added by a future TDLib
        // version fails here until it is mapped; each flag must map
        // independently (catches swapped arguments).
        val tdFields = TdApi.ChatAdministratorRights::class.java.fields
            .filter { it.type == Boolean::class.javaPrimitiveType && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(18, tdFields.size, "TDLib 1.8.68 has 18 administrator rights")
        val modelFlags = ChatAdministratorRights::class.java.declaredFields.filter { it.type == Boolean::class.javaPrimitiveType }
        assertEquals(tdFields.size, modelFlags.size)
        tdFields.forEach { field ->
            val source = TdApi.ChatAdministratorRights().also { field.setBoolean(it, true) }
            val model = TdLibRequestBuilders.administratorRights(source)
            val trueModelFlags = modelFlags.filter { it.isAccessible = true; it.getBoolean(model) }
            assertEquals(1, trueModelFlags.size, "TDLib ${field.name} must map to exactly one model flag")
            val back = TdLibRequestBuilders.administratorRights(model)
            tdFields.forEach { other ->
                assertEquals(other == field, other.getBoolean(back), "${field.name} round-trip changed ${other.name}")
            }
        }
    }

    private val client = mockk<SimpleTelegramClient>()
    private val requests = mutableListOf<TdApi.Function<*>>()
    private val service = TdLibClientService(
        client,
        RateLimiter.ofDefaults("builders-test"),
        CircuitBreaker.ofDefaults("builders-test"),
        SimpleMeterRegistry(),
    )

    private fun respondOk() {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<TdApi.Object>>()
            requests += request
            val response: TdApi.Object = when (request) {
                is TdApi.SendMessage -> TdApi.Message().apply { id = 1; chatId = request.chatId }
                is TdApi.GetChat -> TdApi.Chat().apply { id = request.chatId; title = "Chat" }
                is TdApi.SearchChatMembers -> TdApi.ChatMembers(2, arrayOf(
                    TdApi.ChatMember().apply {
                        memberId = TdApi.MessageSenderUser(7)
                        status = TdApi.ChatMemberStatusAdministrator().apply {
                            rights = TdApi.ChatAdministratorRights().apply {
                                canPinMessages = true
                                canSendWelcomeMessages = true
                            }
                        }
                    },
                    TdApi.ChatMember().apply {
                        memberId = TdApi.MessageSenderUser(8)
                        status = TdApi.ChatMemberStatusCreator()
                    },
                ))
                else -> TdApi.Ok()
            }
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(response))
        }
    }

    @Test
    fun `service sends voice and sticker with TDLib 1_8_68 content shapes`() {
        respondOk()

        service.sendVoice(42, "/tmp/voice.ogg", 3, null)
        service.sendSticker(42, "/tmp/s.webp", "🙂")

        val sends = requests.filterIsInstance<TdApi.SendMessage>()
        assertEquals(2, sends.size)
        val voice = assertIs<TdApi.InputMessageVoiceNote>(sends[0].inputMessageContent)
        assertEquals("/tmp/voice.ogg", (voice.voiceNote.voiceNote as TdApi.InputFileLocal).path)
        assertEquals(3, voice.voiceNote.duration)
        val sticker = assertIs<TdApi.InputMessageSticker>(sends[1].inputMessageContent)
        assertEquals("/tmp/s.webp", (sticker.sticker.sticker as TdApi.InputFileLocal).path)
    }

    @Test
    fun `service forwards welcome-message right when setting admin rights`() {
        respondOk()

        service.setChatMemberAdministratorRights(-100, 7, ChatAdministratorRights(canSendWelcomeMessages = true))
        service.setBotCommands(BotCommandScope(BotCommandScopeKind.DEFAULT), "", listOf(BotCommand("help", "Help")))

        val status = assertIs<TdApi.ChatMemberStatusAdministrator>(requests.filterIsInstance<TdApi.SetChatMemberStatus>().single().status)
        assertTrue(status.rights.canSendWelcomeMessages)
        assertFalse(status.rights.canManageChat)
        val commands = requests.filterIsInstance<TdApi.SetCommands>().single().commands
        assertEquals(listOf("help"), commands.map { it.command })
        assertFalse(commands.single().isEphemeral)
    }

    @Test
    fun `admin listing reads back current rights including welcome messages`() {
        respondOk()

        val admins = service.getChatAdmins(-100, 10)

        assertEquals(
            ChatAdministratorRights(canPinMessages = true, canSendWelcomeMessages = true),
            admins.first { it.userId == 7L }.administratorRights,
        )
        assertNull(admins.first { it.userId == 8L }.administratorRights, "only administrator status carries rights")
    }
}
