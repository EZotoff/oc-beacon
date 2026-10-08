package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.util.CursorCodec
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import javax.inject.Provider

class MessagePaginationUseCaseRepairOnceTest {
    private class Fixture(val sid: String) {
        val repo = mockk<SessionRepository>(relaxed = true)
        val store = mockk<MessageCacheRepository>(relaxed = true)
        val chat = mockk<ChatRepository>(relaxed = true)
        val cursors = mutableListOf<String?>()
        val damaged = MessageWithParts(Message.Assistant(id = "m", sessionId = sid,
            time = TimeInfo(created = 100), parentId = "", modelId = null), emptyList())
        val repaired = damaged.copy(info = (damaged.info as Message.Assistant).copy(modelId = "model"))
        var local = listOf(damaged)
        var remote = listOf(damaged)
        var fail = false
        val useCase = newUseCase()

        init {
            coEvery { repo.getApiVersion(any()) } returns ApiVersion.V1
            coEvery { store.loadRange(sid, 50, null) } coAnswers { local }
            coEvery { store.oldestMessageId(sid) } returns "m"
            coEvery { store.messageCreatedAt("m") } returns 100L
            coEvery { repo.listMessages("server", sid, 50, any()) } coAnswers {
                cursors.add(arg<String?>(3))
                if (fail) Result.failure(IllegalStateException("offline"))
                else Result.success(MessagePage(remote, nextCursor = null))
            }
        }

        fun newUseCase() = MessagePaginationUseCase(chat, repo, store, PaginationCursorPolicyFactory(Provider { repo }))
        suspend fun load() = useCase.loadMessagesForSession("server", sid, 50).getOrThrow()
    }

    @Test fun `unrepairable server metadata triggers full pull only once`() = runTest {
        val f = Fixture("repair-unrepairable")
        f.load()
        f.load()
        assertEquals(listOf(null, CursorCodec.encode("m", 100L)), f.cursors)
    }

    @Test fun `repair flag survives new use case instances within process`() = runTest {
        val f = Fixture("repair-process")
        f.load()
        f.newUseCase().loadMessagesForSession("server", f.sid, 50).getOrThrow()
        assertEquals(listOf(null, CursorCodec.encode("m", 100L)), f.cursors)
    }

    @Test fun `repaired then redamaged cache allows one more repair`() = runTest {
        val f = Fixture("repair-recurrence")
        f.remote = listOf(f.repaired)
        assertEquals("model", (f.load().single().info as Message.Assistant).modelId)
        f.local = listOf(f.repaired)
        f.load()
        f.local = listOf(f.damaged)
        f.remote = listOf(f.damaged)
        f.load()
        f.load()
        assertEquals(listOf(null, CursorCodec.encode("m", 100L), null, CursorCodec.encode("m", 100L)), f.cursors)
    }

    @Test fun `undamaged cache never triggers repair pull`() = runTest {
        val f = Fixture("repair-undamaged")
        f.local = listOf(f.repaired)
        f.remote = listOf(f.repaired)
        f.load()
        f.load()
        assertEquals(listOf(CursorCodec.encode("m", 100L), CursorCodec.encode("m", 100L)), f.cursors)
    }

    @Test fun `failed repair retains attempt and falls back to cache`() = runTest {
        val f = Fixture("repair-offline")
        f.fail = true
        assertEquals(f.local, f.load())
        f.load()
        assertEquals(listOf(null, CursorCodec.encode("m", 100L)), f.cursors)
    }
}
