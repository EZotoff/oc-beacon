package dev.leonardo.ocbeacon.domain.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OperatorViewFreshnessTest {

    private var wall = 1_700_000_000_000L
    private var mono = 500_000L

    private fun evaluator() = OperatorViewFreshness(
        wallMs = { wall },
        monoMs = { mono },
    )

    private fun image(
        generation: Long = 1L,
        lastSeq: Long = 5L,
        producedAtMs: Long = wall,
        cards: Int = 1,
    ): String {
        val producedAt = Instant.ofEpochMilli(producedAtMs).toString()
        val cardList = (1..cards).joinToString(",") { index ->
            """
            {"id":"item-$index","rootLabel":"oc-beacon","root":"/work/oc-beacon",
             "sessionLabel":"ses","reasonText":"why","premiseTexts":["p1"],
             "ageSeconds":10,"severity":"B","jumpAvailable":true,
             "actionClass":"ESCALATE","escalationKind":"DECISION"}
            """.trimIndent()
        }
        return """{"schemaVersion":1,"generation":$generation,"lastSeq":$lastSeq,
            "producedAt":"$producedAt","cards":[$cardList]}""".trimIndent()
    }

    private fun liveView(outcome: OperatorViewReadOutcome): OperatorViewDto =
        (outcome as OperatorViewReadOutcome.Live).view

    @Test
    fun `image 29s old is live and 31s old is frozen stale`() {
        val ev = evaluator()
        assertTrue(ev.read(image(producedAtMs = wall - 29_000)) is OperatorViewReadOutcome.Live)
        val frozen = ev.read(image(generation = 2, lastSeq = 6, producedAtMs = wall - 31_000))
        assertTrue(frozen is OperatorViewReadOutcome.Frozen)
        assertEquals(FreezeReason.STALE, (frozen as OperatorViewReadOutcome.Frozen).reason)
    }

    @Test
    fun `producedAt six seconds in the future freezes with future skew`() {
        val frozen = evaluator().read(image(producedAtMs = wall + 6_000))
        assertTrue(frozen is OperatorViewReadOutcome.Frozen)
        assertEquals(FreezeReason.FUTURE_SKEW, (frozen as OperatorViewReadOutcome.Frozen).reason)
    }

    @Test
    fun `restart with old snapshot on disk freezes stale instead of going live`() {
        // 新评估器实例 = 进程重启；磁盘上的旧镜像已超 30s 预算。
        val frozen = evaluator().read(image(producedAtMs = wall - 60_000))
        assertEquals(FreezeReason.STALE, (frozen as OperatorViewReadOutcome.Frozen).reason)
        assertNull(frozen.lastGood)
    }

    @Test
    fun `same generation with changed lastSeq freezes with missing updates`() {
        val ev = evaluator()
        assertTrue(ev.read(image(generation = 1, lastSeq = 5)) is OperatorViewReadOutcome.Live)
        val changed = ev.read(image(generation = 1, lastSeq = 6))
        assertEquals(FreezeReason.MISSING_UPDATES, (changed as OperatorViewReadOutcome.Frozen).reason)
        assertEquals(1L, (changed.lastGood as OperatorViewDto).generation)
        val regressed = ev.read(image(generation = 1, lastSeq = 4))
        assertEquals(FreezeReason.MISSING_UPDATES, (regressed as OperatorViewReadOutcome.Frozen).reason)
    }

    @Test
    fun `generation regression freezes and frozen outcome keeps last good image`() {
        val ev = evaluator()
        assertTrue(ev.read(image(generation = 3, lastSeq = 9)) is OperatorViewReadOutcome.Live)
        val frozen = ev.read(image(generation = 2, lastSeq = 9))
        assertEquals(FreezeReason.GENERATION_REGRESSION, (frozen as OperatorViewReadOutcome.Frozen).reason)
        assertEquals(3L, (frozen.lastGood as OperatorViewDto).generation)
    }

    @Test
    fun `wall clock jump beyond five seconds freezes and fresh read recovers`() {
        val ev = evaluator()
        assertTrue(ev.read(image()) is OperatorViewReadOutcome.Live)

        // monotonic 只前进 2s，但 wall clock 被拨快 6s → clock-jump。
        mono += 2_000
        wall += 8_000
        val jumped = ev.validity(wall, mono)
        assertEquals(FreezeReason.CLOCK_JUMP, (jumped as OperatorViewReadOutcome.Frozen).reason)

        // 契约：跳变强制重读——重读（新镜像、新锚点）恢复 Live。
        val recovered = ev.read(image(generation = 2, lastSeq = 6, producedAtMs = wall))
        assertTrue(recovered is OperatorViewReadOutcome.Live)
    }

    @Test
    fun `monotonic window beyond thirty seconds marks last good stale`() {
        val ev = evaluator()
        assertTrue(ev.read(image()) is OperatorViewReadOutcome.Live)
        mono += 31_000
        wall += 31_000
        val outcome = ev.validity(wall, mono)
        assertEquals(FreezeReason.STALE, (outcome as OperatorViewReadOutcome.Frozen).reason)
        assertTrue(outcome.lastGood != null)
    }

    @Test
    fun `ten minute quiet period with heartbeat republish stays live`() {
        val ev = evaluator()
        var generation = 1L
        var lastSeq = 5L
        // 心跳重发：同 (generation,lastSeq)，仅 producedAt 刷新。每 25s 一拍，共 10 分钟。
        repeat(24) {
            wall += 25_000
            mono += 25_000
            val outcome = ev.read(image(generation = generation, lastSeq = lastSeq, producedAtMs = wall))
            assertTrue("heartbeat at ${it + 1}/24 should stay live", outcome is OperatorViewReadOutcome.Live)
            liveView(outcome)
        }
    }

    @Test
    fun `invalid json and schema violations freeze with invalid schema`() {
        val ev = evaluator()
        assertEquals(
            FreezeReason.INVALID_SCHEMA,
            (ev.read("not json") as OperatorViewReadOutcome.Frozen).reason,
        )
        assertEquals(
            FreezeReason.INVALID_SCHEMA,
            (ev.read(image(generation = 0)) as OperatorViewReadOutcome.Frozen).reason,
        )
        assertEquals(
            FreezeReason.INVALID_SCHEMA,
            (ev.read(image().replace("\"severity\":\"B\"", "\"severity\":\"Z\"")) as OperatorViewReadOutcome.Frozen).reason,
        )
        assertEquals(
            FreezeReason.INVALID_SCHEMA,
            (ev.read(image(cards = 21)) as OperatorViewReadOutcome.Frozen).reason,
        )
    }
}
