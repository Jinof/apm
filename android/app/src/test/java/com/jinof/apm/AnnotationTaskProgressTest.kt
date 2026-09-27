package com.jinof.apm

import org.junit.Assert.*
import org.junit.Test

class AnnotationTaskProgressTest {
    private val photo = PendingPhoto("photo", "content://photo", "照片.jpg")

    @Test fun etaUsesCompletedAttemptsPendingCountAndCurrentElapsed() {
        var task = AnnotationTaskProgress("全量", 1000, total = 4, skipped = 96)
        assertNull(task.etaSeconds(1000))
        task = task.startPhoto(photo, 1000).finishPhoto(true, 11000)
        assertEquals(30L, task.etaSeconds(11000))
        task = task.startPhoto(photo, 11000)
        assertEquals(25L, task.etaSeconds(16000))
        task = task.finishPhoto(false, 31000)
        assertEquals(1, task.failed)
        assertEquals(1, task.succeeded)
        assertEquals(30L, task.etaSeconds(31000))
        assertNull(task.startPhoto(photo, 31000).etaSeconds(70000))
    }

    @Test fun terminalAndEmptyTasksNeverKeepRunningPhotoOrClock() {
        val task = AnnotationTaskProgress("所选", 1000, total = 1).startPhoto(photo, 1000)
        for (fatal in listOf(false, true)) {
            val end = task.finish(3000, fatal)
            assertFalse(end.active)
            assertNull(end.currentPhotoId)
            assertEquals(2000L, end.elapsedMillis(9000))
            assertNull(end.etaSeconds(9000))
        }
        assertNull(AnnotationTaskProgress("全量", 0, total = 0).finish(1).etaSeconds(10))
    }

    @Test fun logsAreBoundedAndErrorsDoNotExposeRawMessages() {
        var task = AnnotationTaskProgress("全量", 0)
        repeat(100) { task = task.log("事件 $it", it.toLong()) }
        assertEquals(40, task.logs.size)
        assertTrue(task.logs.last().contains("事件 99"))
        val secret = "Bearer very-private-api-key"
        assertFalse(annotationFailureMessage(IllegalStateException(secret)).contains(secret))
        assertFalse(annotationFailureMessage(java.io.IOException(secret)).contains(secret))
    }
}
