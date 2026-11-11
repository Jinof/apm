package com.jinof.apm

import org.junit.Assert.*
import org.junit.Test

class AnnotationTaskProgressTest {
    private val photo = PendingPhoto("photo", "content://photo", "照片.jpg")

    @Test fun etaUsesCompletedAttemptsPendingCountAndCurrentElapsed() {
        var task = AnnotationTaskProgress("全量", 1000, total = 4, skipped = 96)
        assertNull(task.etaSeconds(1000))
        task = task.startPhoto(photo, 1000).finishPhoto(photo.photoId, true, 11000)
        assertEquals(30L, task.etaSeconds(11000))
        task = task.startPhoto(photo, 11000)
        assertEquals(25L, task.etaSeconds(16000))
        task = task.finishPhoto(photo.photoId, false, 31000)
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

    @Test fun outOfOrderProgressKeepsAllActivePhotosAndParallelEta() {
        val first = PendingPhoto("first", "content://first", "a.jpg")
        val second = PendingPhoto("second", "content://second", "b.jpg")
        var task = AnnotationTaskProgress("所选", 0, total = 4, concurrency = 2)
            .startPhoto(first, 0).startPhoto(second, 0)
        assertEquals(setOf("first", "second"), task.activePhotoIds)
        assertNull(task.etaSeconds(500))
        task = task.finishPhoto("second", false, 10000)
        assertEquals(setOf("first"), task.activePhotoIds)
        assertEquals(1, task.failed)
        assertEquals(10L, task.etaSeconds(10000))
        task = task.finishPhoto("first", true, 12000)
        assertEquals(1, task.succeeded)
        assertTrue(task.activePhotos.isEmpty())
        assertEquals(11L, task.etaSeconds(12000))
        assertTrue(task.finish(15000).activePhotos.isEmpty())
    }

    @Test fun groupedProgressCountsRequestsAndPhotosSeparatelyIncludingShortTail() {
        val photos = (0..8).map { PendingPhoto("p$it", "content://photo/$it", "$it.jpg") }
        var task = AnnotationTaskProgress("所选", 0, total = 9, concurrency = 2, mergeCount = 8)
            .startBatch(photos.take(8), 0).startBatch(photos.takeLast(1), 0)
        assertEquals(2, task.activeBatches.size); assertEquals(9, task.activePhotos.size)
        task = task.finishBatch("p8", false, 5000)
        assertEquals(1, task.failed); assertEquals(1, task.completedRequests)
        assertEquals(8, task.activePhotos.size)
        task = task.finishBatch("p0", true, 10000)
        assertEquals(8, task.succeeded); assertEquals(9, task.completed)
        assertEquals(2, task.completedRequests); assertTrue(task.activePhotos.isEmpty())
        assertNull(task.etaSeconds(10000))
        val serial = AnnotationTaskProgress("全量", 0, total = 9, concurrency = 1, mergeCount = 8)
            .startBatch(photos.take(8), 0).finishBatch("p0", true, 10000)
        assertEquals(10L, serial.etaSeconds(10000))
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
