package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.ChatFolderInfo
import dev.telegrammcp.server.model.ChatFolderListing
import it.tdlight.jni.TdApi
import java.util.concurrent.atomic.AtomicReference

/**
 * TDLib publishes the account's folder inventory as [TdApi.UpdateChatFolders]
 * rather than exposing a list function. The state is registered before the
 * TDLib client starts so the initial update is retained for MCP reads.
 */
class ChatFolderState {

    private val listing = AtomicReference(ChatFolderListing(emptyList()))

    fun replace(update: TdApi.UpdateChatFolders) {
        listing.set(
            ChatFolderListing(
                folders = update.chatFolders.orEmpty().map { folder ->
                    ChatFolderInfo(
                        id = folder.id,
                        title = folder.name?.text?.text.orEmpty(),
                        iconName = folder.icon?.name?.takeIf { it.isNotBlank() },
                        colorId = folder.colorId,
                        isShareable = folder.isShareable,
                        hasMyInviteLinks = folder.hasMyInviteLinks,
                    )
                },
                mainChatListPosition = update.mainChatListPosition,
                tagsEnabled = update.areTagsEnabled,
                initialized = true,
            ),
        )
    }

    fun snapshot(): ChatFolderListing = listing.get()
}
