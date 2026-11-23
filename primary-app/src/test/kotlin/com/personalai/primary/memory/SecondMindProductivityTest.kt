package com.personalai.primary.memory

import com.personalai.primary.audio.AudioRetentionPolicy
import com.personalai.primary.data.db.AsrProcessingStatus
import com.personalai.primary.data.db.CertaintyLevel
import com.personalai.primary.data.db.EntityNodeEntity
import com.personalai.primary.data.db.EntityNodeType
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.SegmentWithTranscript
import com.personalai.primary.data.db.SyncedSegmentEntity
import com.personalai.primary.data.db.TaskReminderEntity
import com.personalai.primary.data.db.TaskReminderStatus
import com.personalai.primary.data.db.TranscriptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SecondMindProductivityTest {

    @Test
    fun `audio retention policies calculate exact 1, 3, and 5 day cutoffs and null for Keep Forever`() {
        val nowMs = 1_760_000_000_000L
        assertEquals(nowMs - TimeUnit.DAYS.toMillis(1), AudioRetentionPolicy.DAYS_1.cutoffTimestampMs(nowMs))
        assertEquals(nowMs - TimeUnit.DAYS.toMillis(3), AudioRetentionPolicy.DAYS_3.cutoffTimestampMs(nowMs))
        assertEquals(nowMs - TimeUnit.DAYS.toMillis(5), AudioRetentionPolicy.DAYS_5.cutoffTimestampMs(nowMs))
        assertNull(AudioRetentionPolicy.KEEP_FOREVER.cutoffTimestampMs(nowMs))
    }

    @Test
    fun `entity hygiene filters LLM placeholder and Hinglish stopword junk while preserving real entities`() {
        assertFalse(EntityHygiene.isValidEntityName("N/A"))
        assertFalse(EntityHygiene.isValidEntityName("none"))
        assertFalse(EntityHygiene.isValidEntityName("Koi"))
        assertFalse(EntityHygiene.isValidEntityName("Ham"))
        assertFalse(EntityHygiene.isValidEntityName("Hoga"))
        assertFalse(EntityHygiene.isValidEntityName("Kuchh"))
        assertFalse(EntityHygiene.isValidEntityName("a"))

        assertTrue(EntityHygiene.isValidEntityName("Patna"))
        assertTrue(EntityHygiene.isValidEntityName("Mummy"))
        assertTrue(EntityHygiene.isValidEntityName("Rahul Verma"))
        assertTrue(EntityHygiene.isValidEntityName("Project Apollo"))
    }

    @Test
    fun `insights engine computes 7-day activity, streak, category shares, language split, and clean entities`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 22, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayMs = cal.timeInMillis
        val yesterdayMs = todayMs - TimeUnit.DAYS.toMillis(1)
        val twoDaysAgoMs = todayMs - TimeUnit.DAYS.toMillis(2)

        fun seg(id: String, seq: Long, ts: Long, durMs: Long, lang: String, text: String): SegmentWithTranscript {
            val s = SyncedSegmentEntity(
                segmentId = id,
                sessionId = "s1",
                sequenceNumber = seq,
                startTimestampMs = ts,
                endTimestampMs = ts + durMs,
                durationMs = durMs,
                localEncryptedAudioPath = "s1/$id.enc",
                wavSizeBytes = 32_000L,
                checksum = "abc",
                syncedAtMs = ts,
                asrStatus = AsrProcessingStatus.COMPLETED
            )
            val t = TranscriptEntity(
                transcriptId = "tr-$id",
                segmentId = id,
                sessionId = "s1",
                text = text,
                language = lang,
                startTimeMs = ts,
                endTimeMs = ts + durMs,
                confidence = 0.9f,
                asrProvider = "Android On-Device",
                createdAtMs = ts
            )
            return SegmentWithTranscript(s, t)
        }

        val segments = listOf(
            seg("s1", 1, todayMs, 15_000L, "hi", "Patna ghumne jana hai"),
            seg("s2", 2, yesterdayMs, 30_000L, "en", "Finalize Q4 roadmap"),
            seg("s3", 3, twoDaysAgoMs, 20_000L, "hi-en", "Mummy ko call karna hai")
        )
        val memories = listOf(
            MemoryItemEntity("m1", MemoryCategory.DECISION, "Ship v2", "Decided to ship v2", "tr-s2", "s2", "s1", yesterdayMs, 0.9f, CertaintyLevel.EXPLICIT, "Roadmap", yesterdayMs, isPinned = true),
            MemoryItemEntity("m2", MemoryCategory.IDEA, "Patna Trip", "Visit Patna after rains", "tr-s1", "s1", "s1", todayMs, 0.85f, CertaintyLevel.INFERRED_MEDIUM, "Patna", todayMs, isPinned = false),
            MemoryItemEntity("m3", MemoryCategory.TASK, "Call Mummy", "Call Mummy tonight", "tr-s3", "s3", "s1", twoDaysAgoMs, 0.9f, CertaintyLevel.EXPLICIT, "Mummy", twoDaysAgoMs, isPinned = false)
        )
        val tasks = listOf(
            TaskReminderEntity("t1", "m3", "tr-s3", "Call Mummy", "Call tonight", status = TaskReminderStatus.OPEN, createdAtMs = twoDaysAgoMs, priority = 2),
            TaskReminderEntity("t2", "m1", "tr-s2", "Ship v2", "Done", status = TaskReminderStatus.DONE, createdAtMs = yesterdayMs, priority = 1)
        )
        val entities = listOf(
            EntityNodeEntity("e1", "N/A", "n/a", EntityNodeType.TOPIC, 5, todayMs),
            EntityNodeEntity("e2", "Patna", "patna", EntityNodeType.PLACE, 3, todayMs),
            EntityNodeEntity("e3", "Koi", "koi", EntityNodeType.TOPIC, 2, todayMs)
        )

        val snap = SecondMindInsightsEngine.compute(segments, memories, tasks, entities, nowMs = todayMs)
        assertEquals("Good afternoon", snap.greeting)
        assertEquals(1, snap.todayClipsCount)
        assertEquals(15L, snap.todaySpokenSeconds)
        assertEquals(65L, snap.totalSpokenSeconds)
        assertEquals(3, snap.captureStreakDays)
        assertEquals(1, snap.pinnedCount)
        assertEquals(1, snap.openTasksCount)
        assertEquals(1, snap.completedTasksCount)
        assertEquals(0.5f, snap.taskCompletionRate, 0.01f)
        assertEquals(7, snap.weeklyBuckets.size)
        assertEquals("Today", snap.weeklyBuckets.last().dayLabel)
        assertEquals(3, snap.languageBreakdown.size)
        assertEquals(listOf("Patna"), snap.cleanTopEntities.map { it.name })
    }
}
