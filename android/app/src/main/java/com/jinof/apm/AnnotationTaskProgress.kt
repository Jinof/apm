package com.jinof.apm

data class ActiveAnnotationBatch(val photos: Map<String, String>, val startedAt: Long)

data class ActiveAnnotationPhoto(val name: String, val startedAt: Long)

/** A memory-only projection; timestamps are monotonic milliseconds, never wall-clock dates. */
data class AnnotationTaskProgress(
    val scope: String,
    val startedAt: Long,
    val phase: String = "准备中",
    val total: Int? = null,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val concurrency: Int = 1,
    val mergeCount: Int = 1,
    val activeBatches: Map<String, ActiveAnnotationBatch> = emptyMap(),
    val completedRequests: Int = succeeded + failed,
    val attemptMillis: Long = 0,
    val finishedAt: Long? = null,
    val logs: List<String> = emptyList(),
) {
    init { require(concurrency in 1..8 && mergeCount in 1..8) }
    val activePhotos = activeBatches.values.flatMap { batch ->
        batch.photos.map { (id, name) -> id to ActiveAnnotationPhoto(name, batch.startedAt) }
    }.toMap()
    val currentPhotoId: String? get() = activePhotos.keys.firstOrNull()
    val currentPhotoName: String? get() = activePhotos.values.firstOrNull()?.name
    val activePhotoIds: Set<String> get() = activePhotos.keys
    val active: Boolean get() = finishedAt == null
    val completed: Int get() = succeeded + failed

    fun elapsedMillis(now: Long): Long = ((finishedAt ?: now) - startedAt).coerceAtLeast(0)

    fun etaSeconds(now: Long): Long? {
        val remaining = total?.let { (it + mergeCount - 1) / mergeCount - completedRequests } ?: return null
        if (!active || remaining <= 0 || completedRequests == 0) return null
        val activeElapsed = activeBatches.values.sumOf { (now - it.startedAt).coerceAtLeast(0) }
        val estimate = (attemptMillis.toDouble() / completedRequests * remaining - activeElapsed) /
            minOf(concurrency, remaining).coerceAtLeast(1)
        return if (estimate > 0) kotlin.math.ceil(estimate / 1000).toLong() else null
    }

    fun log(message: String, now: Long): AnnotationTaskProgress = copy(
        logs = (logs + "${formatAnnotationDuration(elapsedMillis(now) / 1000)} · $message").takeLast(40),
    )

    fun startPhoto(photo: PendingPhoto, now: Long): AnnotationTaskProgress = startBatch(listOf(photo), now)

    fun startBatch(photos: List<PendingPhoto>, now: Long): AnnotationTaskProgress {
        require(photos.size in 1..mergeCount && photos.map { it.photoId }.distinct().size == photos.size)
        check(active && activeBatches.size < concurrency && photos.none { it.photoId in activePhotos })
        val names = photos.associate { it.photoId to it.displayName.replace(Regex("[\\r\\n\\t]"), " ").take(120) }
        return copy(phase = "标注中", activeBatches = activeBatches + (photos.first().photoId to ActiveAnnotationBatch(names, now)))
            .log("开始 · ${photos.size} 张 · ${names.values.first()}", now)
    }

    fun finishPhoto(photoId: String, success: Boolean, now: Long): AnnotationTaskProgress {
        require(activeBatches[photoId]?.photos?.size == 1)
        return finishBatch(photoId, success, now)
    }

    fun finishBatch(batchId: String, success: Boolean, now: Long): AnnotationTaskProgress {
        val batch = requireNotNull(activeBatches[batchId])
        return copy(
            succeeded = succeeded + if (success) batch.photos.size else 0,
            failed = failed + if (success) 0 else batch.photos.size,
            completedRequests = completedRequests + 1,
            attemptMillis = attemptMillis + (now - batch.startedAt).coerceAtLeast(1),
            activeBatches = activeBatches - batchId,
        )
    }

    fun finish(now: Long, fatal: Boolean = false): AnnotationTaskProgress = copy(
        phase = if (fatal) "任务失败" else if (failed > 0) "完成（有失败）" else "已完成",
        finishedAt = now, activeBatches = emptyMap(),
    )

}

internal fun formatAnnotationDuration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}小时${seconds % 3600 / 60}分"
    seconds >= 60 -> "${seconds / 60}分${seconds % 60}秒"
    else -> "${seconds.coerceAtLeast(0)}秒"
}

internal fun annotationFailureMessage(error: Exception): String = when (error) {
    is ApiRequestException -> error.message ?: "API 请求失败"
    is java.net.SocketTimeoutException -> "请求超时"
    is java.io.IOException -> "图片读取或网络通信失败"
    is InterruptedException -> "任务已中断"
    else -> "标注或保存失败，原有数据已保留"
}
