package dev.telegrammcp.server.security

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source-level regression inventory for Telegram calls whose target chat is
 * absent from caller input, derived by Telegram, or created by the operation.
 *
 * This intentionally checks one top-level tool class at a time. A guard in a
 * neighbouring class in the same Kotlin file must not satisfy another tool's
 * contract.
 */
class ChatAllowListCoverageTest {

    @Test
    fun `account-wide derived and create-join calls keep their class-local allow-list contract`() {
        val classes = toolTypeSources()
        val violations = contracts.mapNotNull { contract ->
            val source = classes[contract.className]
                ?: return@mapNotNull "${contract.className}: tool class was not found"
            val missingTokens = contract.requiredInOrder.filterNot(source::contains)
            if (missingTokens.isNotEmpty()) {
                return@mapNotNull "${contract.className}: missing ${missingTokens.joinToString()}"
            }
            if (!containsInOrder(source, contract.requiredInOrder)) {
                "${contract.className}: expected sequence ${contract.requiredInOrder.joinToString(" -> ")}"
            } else {
                null
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Telegram operations can bypass TELEGRAM_ALLOWED_CHAT_IDS:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `sensitive Telegram call inventory matches the reviewed tool contracts`() {
        val discovered = toolTypeSources().flatMap { (className, source) ->
            telegramCallPattern.findAll(source)
                .map { it.groupValues[1] }
                .filter { it in sensitiveTelegramCalls }
                .map { "$className#$it" }
                .toList()
        }.toSet()
        val expected = contracts
            .flatMap { contract ->
                contract.requiredInOrder.mapNotNull { token ->
                    telegramCallPattern.find(token)?.groupValues?.get(1)
                        ?.takeIf { it in sensitiveTelegramCalls }
                        ?.let { "${contract.className}#$it" }
                }
            }
            .toSet()

        assertEquals(
            expected,
            discovered,
            "Sensitive Telegram call inventory drifted. Add a class-local allow-list contract for every new call site.",
        )
    }

    @Test
    fun `every Telegram client method has an explicit access-policy classification`() {
        val clientSource = locateProjectRoot()
            .resolve(
                Paths.get(
                    "src",
                    "main",
                    "kotlin",
                    "dev",
                    "telegrammcp",
                    "server",
                    "client",
                    "TelegramClientService.kt",
                ),
            )
            .readText()
        val discovered = clientMethodPattern.findAll(clientSource)
            .map { it.groupValues[1] }
            .toSet()
        val classified = clientMethodPolicies.values.flatten()
        val duplicates = classified.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys

        assertTrue(duplicates.isEmpty(), "Telegram client methods have multiple policy classes: ${duplicates.sorted()}")
        assertEquals(
            discovered,
            classified.toSet(),
            "TelegramClientService policy inventory drifted. Classify every new method before exposing it through a tool.",
        )
    }

    private fun containsInOrder(source: String, tokens: List<String>): Boolean {
        var offset = 0
        for (token in tokens) {
            val index = source.indexOf(token, startIndex = offset)
            if (index < 0) return false
            offset = index + token.length
        }
        return true
    }

    private fun toolTypeSources(): Map<String, String> = buildMap {
        toolSourceFiles().forEach { file ->
            val source = file.readText()
            val declarations = topLevelTypePattern.findAll(source).toList()
            declarations.forEachIndexed { index, declaration ->
                val className = declaration.groupValues[2]
                val end = declarations.getOrNull(index + 1)?.range?.first ?: source.length
                put(className, source.substring(declaration.range.first, end))
            }
        }
    }

    private fun toolSourceFiles(): List<Path> {
        val toolRoot = locateProjectRoot()
            .resolve(Paths.get("src", "main", "kotlin", "dev", "telegrammcp", "server", "tool"))
        check(Files.isDirectory(toolRoot)) { "Tool source directory not found: $toolRoot" }
        Files.walk(toolRoot).use { stream ->
            return stream.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .toList()
        }
    }

    private fun locateProjectRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        error("Project root not found upwards from ${System.getProperty("user.dir")}")
    }

    private data class AllowListContract(
        val className: String,
        val requiredInOrder: List<String>,
    )

    companion object {
        private val telegramCallPattern = Regex("""telegramClient\.([A-Za-z0-9_]+)\(""")
        private val clientMethodPattern = Regex("""(?m)^\s*fun\s+([A-Za-z0-9_]+)\s*\(""")
        private val topLevelTypePattern = Regex(
            """(?m)^(?:(?:public|private|internal|protected)\s+)?(?:(?:data|sealed|abstract|open)\s+)?(class|object|interface)\s+([A-Za-z0-9_]+)""",
        )

        private val sensitiveTelegramCalls = setOf(
            "getChats",
            "getDrafts",
            "searchGlobal",
            "getGroupsInCommon",
            "getLastInteractionWithContact",
            "getChatFolder",
            "listChatFolders",
            "searchPublicChats",
            "getMessageByLink",
            "getPrivacySettingRules",
            "setPrivacySettingRules",
            "createChatFolder",
            "updateChatFolder",
            "deleteChatFolder",
            "reorderChatFolders",
            "joinChatByInviteLink",
            "joinPublicChat",
            "createBasicGroup",
            "createSupergroupOrChannel",
        )

        private val contracts = listOf(
            // Account-wide result sources are filtered before returning data.
            AllowListContract(
                "ListChatsTool",
                listOf("telegramClient.getChats(", "guardrailService.isChatAllowed("),
            ),
            AllowListContract(
                "GetDraftsTool",
                listOf("telegramClient.getDrafts(", "guardrailService.isChatAllowed("),
            ),
            AllowListContract(
                "SearchGlobalTool",
                listOf("telegramClient.searchGlobal(", "guardrailService.isChatAllowed("),
            ),
            AllowListContract(
                "GetCommonChatsTool",
                listOf("telegramClient.getGroupsInCommon(", "guardrailService.isChatAllowed("),
            ),
            AllowListContract(
                "SearchPublicChatsTool",
                listOf("telegramClient.searchPublicChats(", "guardrailService.isChatAllowed("),
            ),
            AllowListContract(
                "DiscoverPublicChatsForResearchTool",
                listOf("telegramClient.searchPublicChats(", "guardrailService.isChatAllowed("),
            ),
            AllowListContract(
                "GetPrivacySettingsTool",
                listOf("telegramClient.getPrivacySettingRules(", "guardrailService::isChatAllowed"),
            ),

            // Telegram-derived IDs are validated without echoing rejected IDs.
            AllowListContract(
                "GetLastInteractionTool",
                listOf(
                    "guardrailService.validateDerivedChatAccess(",
                    "telegramClient.getLastInteractionWithContact(",
                    "guardrailService.validateDerivedChatAccess(",
                ),
            ),
            AllowListContract(
                "MessageFromLinkTool",
                listOf("telegramClient.getMessageByLink(", "guardrailService.validateDerivedChatAccess("),
            ),
            AllowListContract(
                "ResolveUsernameTool",
                listOf(
                    "entityResolver.resolve(",
                    "guardrailService.validateDerivedChatAccess(",
                    "telegramClient.getChat(",
                ),
            ),
            AllowListContract(
                "GetChatFolderTool",
                listOf("telegramClient.getChatFolder(", "ChatFolderInputs.validateDerivedFolderChatAccess("),
            ),
            AllowListContract(
                "ListChatFoldersTool",
                listOf(
                    "telegramClient.listChatFolders(",
                    "ChatFolderInputs.isDerivedFolderAllowed(",
                    "telegramClient.getChatFolder(",
                ),
            ),

            // Folder writes validate all explicit/derived members in the same class.
            AllowListContract(
                "ConfigureChatFolderTool",
                listOf("ChatFolderInputs.definition(", "telegramClient.createChatFolder("),
            ),
            AllowListContract(
                "ConfigureChatFolderTool",
                listOf("ChatFolderInputs.definition(", "telegramClient.updateChatFolder("),
            ),
            AllowListContract(
                "DeleteChatFolderTool",
                listOf(
                    "ChatFolderInputs.validateDerivedFolderChatAccess(",
                    "telegramClient.getChatFolder(",
                    "telegramClient.deleteChatFolder(",
                ),
            ),
            AllowListContract(
                "ReorderChatFoldersTool",
                listOf(
                    "ChatFolderInputs.validateDerivedFolderChatAccess(",
                    "telegramClient.getChatFolder(",
                    "telegramClient.reorderChatFolders(",
                ),
            ),
            AllowListContract(
                "SetPrivacySettingsTool",
                listOf("P3ToolInputs.privacyRules(", "telegramClient.setPrivacySettingRules("),
            ),
            AllowListContract(
                "ChatFolderInputs",
                listOf(
                    "fun definition(",
                    "validateFolderChatAccess(definition, guardrailService)",
                    "guardrailService::validateChatAccess",
                    "fun isDerivedFolderAllowed(",
                    "guardrailService::isChatAllowed",
                ),
            ),
            AllowListContract(
                "P3ToolInputs",
                listOf(
                    "fun privacyRules(",
                    "chatIds(arguments",
                    "guardrailService::validateChatAccess",
                ),
            ),

            // A known target is validated before joining; unknowable/new targets
            // fail closed whenever a static allow-list is configured.
            AllowListContract(
                "SubscribePublicChannelTool",
                listOf(
                    "guardrailService.validateDerivedChatAccess(targetChatId)",
                    "telegramClient.joinPublicChat(channel, targetChatId)",
                ),
            ),
            AllowListContract(
                "JoinChatByLinkTool",
                listOf("guardrailService.requireUnrestrictedChatScope(", "telegramClient.joinChatByInviteLink("),
            ),
            AllowListContract(
                "CreateGroupTool",
                listOf("guardrailService.requireUnrestrictedChatScope(", "telegramClient.createBasicGroup("),
            ),
            AllowListContract(
                "CreateChannelTool",
                listOf("guardrailService.requireUnrestrictedChatScope(", "telegramClient.createSupergroupOrChannel("),
            ),
            AllowListContract(
                "CreateSupergroupTool",
                listOf("guardrailService.requireUnrestrictedChatScope(", "telegramClient.createSupergroupOrChannel("),
            ),
        )

        /**
         * Exhaustive policy inventory for the Telegram client boundary.
         *
         * The source-contract checks above exercise methods whose chat target
         * is not a simple caller-supplied ID. Existing direct-chat guard tests
         * cover [DIRECT_CHAT_INPUT]. The remaining categories state why the
         * chat allow-list is either enforced by a structured helper or is not
         * the applicable account boundary.
         */
        private val clientMethodPolicies = mapOf(
            ClientMethodPolicy.DIRECT_CHAT_INPUT to setOf(
                "addChatMembers",
                "addReaction",
                "archiveChat",
                "banChatMember",
                "cancelScheduledMessage",
                "clearDraft",
                "closeForumTopic",
                "createForumTopic",
                "createInviteLink",
                "deleteChatPhoto",
                "deleteMessages",
                "downloadMedia",
                "editForumTopic",
                "editMessage",
                "forwardMessages",
                "getBannedChatMembers",
                "getChat",
                "getChatAdmins",
                "getChatEventLog",
                "getChatInviteLinks",
                "getChatMembers",
                "getChatPermissions",
                "getHistory",
                "getMediaInfo",
                "getMessageContext",
                "getMessageLink",
                "getMessageReactions",
                "getMessageViewers",
                "getPinnedMessages",
                "getScheduledMessages",
                "leaveChat",
                "listForumTopics",
                "listInlineButtons",
                "muteChat",
                "pinMessage",
                "pressInlineButton",
                "removeReaction",
                "reopenForumTopic",
                "replyToMessage",
                "rescheduleMessage",
                "revokeChatInviteLink",
                "saveDraft",
                "scheduleMessage",
                "searchMessages",
                "sendFile",
                "sendMessage",
                "sendPoll",
                "sendSticker",
                "sendVoice",
                "setChatDescription",
                "setChatMemberAdmin",
                "setChatMemberAdministratorRights",
                "setChatMemberPermissions",
                "setChatPermissions",
                "setChatPhoto",
                "setChatSlowModeDelay",
                "setChatTitle",
                "setForumTopicsEnabled",
                "setPollAnswer",
                "stopPoll",
                "transcribeVoiceNote",
                "unarchiveChat",
                "unbanChatMember",
                "unmuteChat",
                "unpinMessage",
                "viewMessages",
            ),
            ClientMethodPolicy.ACCOUNT_WIDE_FILTER to setOf(
                "getChats",
                "getDrafts",
                "getGroupsInCommon",
                "listChatFolders",
                "getPrivacySettingRules",
                "searchGlobal",
                "searchPublicChats",
            ),
            ClientMethodPolicy.POST_RESULT_VALIDATE to setOf(
                "getChatFolder",
                "getLastInteractionWithContact",
                "getMessageByLink",
            ),
            ClientMethodPolicy.CREATE_OR_JOIN to setOf(
                "createBasicGroup",
                "createSupergroupOrChannel",
                "joinChatByInviteLink",
                "joinPublicChat",
            ),
            ClientMethodPolicy.STRUCTURED_CHAT_INPUT to setOf(
                "createChatFolder",
                "deleteChatFolder",
                "getBotCommands",
                "reorderChatFolders",
                "setBotCommands",
                "setPrivacySettingRules",
                "updateChatFolder",
            ),
            ClientMethodPolicy.RESOLVED_IDENTIFIER to setOf(
                "resolvePhone",
                "resolveSelfChat",
                "resolveUsername",
            ),
            ClientMethodPolicy.USER_SCOPED to setOf(
                "addContact",
                "blockUser",
                "getBlockedUsers",
                "getContacts",
                "getMe",
                "getUser",
                "getUserProfilePhotos",
                "getUserStatus",
                "removeContacts",
                "searchContactsByQuery",
                "unblockUser",
            ),
            ClientMethodPolicy.ACCOUNT_SCOPED to setOf(
                "deleteProfilePhoto",
                "getInstalledStickerSets",
                "setProfilePhoto",
                "updateProfile",
            ),
        )
    }

    private enum class ClientMethodPolicy {
        DIRECT_CHAT_INPUT,
        ACCOUNT_WIDE_FILTER,
        POST_RESULT_VALIDATE,
        CREATE_OR_JOIN,
        STRUCTURED_CHAT_INPUT,
        RESOLVED_IDENTIFIER,
        USER_SCOPED,
        ACCOUNT_SCOPED,
    }
}
