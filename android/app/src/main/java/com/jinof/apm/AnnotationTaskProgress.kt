package com.jinof.apm

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
    val activePhotos: Map<String, ActiveAnnotationPhoto> = emptyMap(),
    val attemptMillis: Long = 0,
    val finishedAt: Long? = null,
    val logs: List<String> = emptyList(),
) {
    val currentPhotoId: String? get() = activePhotos.keys.firstOrNull()
    val currentPhotoName: String? get() = activePhotos.values.firstOrNull()?.name
    val activePhotoIds: Set<String> get() = activePhotos.keys
    val active: Boolean get() = finishedAt == null
    val completed: Int get() = succeeded + failed

    fun elapsedMillis(now: Long): Long = ((finishedAt ?: now) - startedAt).coerceAtLeast(0)

    fun etaSeconds(now: Long): Long? {
        val remaining = total?.minus(completed) ?: return null
        if (!active || remaining <= 0 || completed == 0) return null
        val activeElapsed = activePhotos.values.sumOf { (now - it.startedAt).coerceAtLeast(0) }
        val estimate = (attemptMillis.toDouble() / completed * remaining - activeElapsed) /
            minOf(concurrency, remaining).coerceAtLeast(1)
        return if (estimate > 0) kotlin.math.ceil(estimate / 1000).toLong() else null
    }

    fun log(message: String, now: Long): AnnotationTaskProgress = copy(
        logs = (logs + "${formatAnnotationDuration(elapsedMillis(now) / 1000)} · $message").takeLast(40),
    )

    fun startPhoto(photo: PendingPhoto, now: Long): AnnotationTaskProgress {
        check(active && photo.photoId !in activePhotos && activePhotos.size < concurrency)
        val name = photo.displayName.replace(Regex("[\\r\\n\\t]"), " ").take(120)
        return copy(phase = "标注中", activePhotos = activePhotos + (photo.photoId to ActiveAnnotationPhoto(name, now)))
            .log("开始 · $name", now)
    }

    fun finishPhoto(photoId: String, success: Boolean, now: Long): AnnotationTaskProgress {
        val photo = requireNotNull(activePhotos[photoId])
        return copy(
            succeeded = succeeded + if (success) 1 else 0,
            failed = failed + if (success) 0 else 1,
            attemptMillis = attemptMillis + (now - photo.startedAt).coerceAtLeast(1),
            activePhotos = activePhotos - photoId,
        )
    }

    fun finish(now: Long, fatal: Boolean = false): AnnotationTaskProgress = copy(
        phase = if (fatal) "任务失败" else if (failed > 0) "完成（有失败）" else "已完成",
        finishedAt = now, activePhotos = emptyMap(),
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
