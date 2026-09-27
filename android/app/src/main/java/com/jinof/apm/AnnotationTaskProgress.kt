package com.jinof.apm

/** A memory-only projection; timestamps are monotonic milliseconds, never wall-clock dates. */
data class AnnotationTaskProgress(
    val scope: String,
    val startedAt: Long,
    val phase: String = "准备中",
    val total: Int? = null,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val currentPhotoId: String? = null,
    val currentPhotoName: String? = null,
    val currentStartedAt: Long? = null,
    val attemptMillis: Long = 0,
    val finishedAt: Long? = null,
    val logs: List<String> = emptyList(),
) {
    val active: Boolean get() = finishedAt == null
    val completed: Int get() = succeeded + failed

    fun elapsedMillis(now: Long): Long = ((finishedAt ?: now) - startedAt).coerceAtLeast(0)

    fun etaSeconds(now: Long): Long? {
        val remaining = total?.minus(completed) ?: return null
        if (!active || remaining <= 0 || completed == 0) return null
        val currentElapsed = currentStartedAt?.let { (now - it).coerceAtLeast(0) } ?: 0
        val estimate = attemptMillis.toDouble() / completed * remaining - currentElapsed
        return if (estimate > 0) kotlin.math.ceil(estimate / 1000).toLong() else null
    }

    fun log(message: String, now: Long): AnnotationTaskProgress = copy(
        logs = (logs + "${formatAnnotationDuration(elapsedMillis(now) / 1000)} · $message").takeLast(40),
    )

    fun startPhoto(photo: PendingPhoto, now: Long): AnnotationTaskProgress = copy(
        phase = "标注中", currentPhotoId = photo.photoId,
        currentPhotoName = photo.displayName.replace(Regex("[\\r\\n\\t]"), " ").take(120),
        currentStartedAt = now,
    ).log("${completed + 1}/${total ?: 0} · ${photo.displayName.take(120)}", now)

    fun finishPhoto(success: Boolean, now: Long): AnnotationTaskProgress = copy(
        succeeded = succeeded + if (success) 1 else 0,
        failed = failed + if (success) 0 else 1,
        attemptMillis = attemptMillis + (now - (currentStartedAt ?: now)).coerceAtLeast(1),
        currentPhotoId = null, currentPhotoName = null, currentStartedAt = null,
    )

    fun finish(now: Long, fatal: Boolean = false): AnnotationTaskProgress = copy(
        phase = if (fatal) "任务失败" else if (failed > 0) "完成（有失败）" else "已完成",
        finishedAt = now, currentPhotoId = null, currentPhotoName = null, currentStartedAt = null,
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
