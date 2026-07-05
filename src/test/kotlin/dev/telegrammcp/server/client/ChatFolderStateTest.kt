package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatFolderStateTest {

    @Test
    fun `retains TDLib folder update for list tool reads`() {
        val state = ChatFolderState()
        val folder = TdApi.ChatFolderInfo(
            4,
            TdApi.ChatFolderName(TdApi.FormattedText("Work", emptyArray()), false),
            TdApi.ChatFolderIcon("Briefcase"),
            3,
            true,
            false,
        )

        state.replace(TdApi.UpdateChatFolders(arrayOf(folder), 2, true))
        val listing = state.snapshot()

        assertTrue(listing.initialized)
        assertEquals(2, listing.mainChatListPosition)
        assertTrue(listing.tagsEnabled)
        assertEquals("Work", listing.folders.single().title)
        assertEquals("Briefcase", listing.folders.single().iconName)
    }
}
