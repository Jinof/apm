package com.jinof.apm

import android.graphics.Bitmap
import android.graphics.Color

/** Uses a generated image and the production contract, never the photo library or database. */
internal object ApiConnectionTest {
    fun run(candidate: InferenceConfig): String {
        val api = ExternalApiClient(candidate, readTimeoutMillis = 60_000)
        val started = System.nanoTime()
        val response = api.use {
            if (candidate.mergeCount == 1) {
                val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val encoded = try {
                    image.eraseColor(Color.rgb(50, 110, 210))
                    VlmThumbnailEncoder.encode(image, 64).base64
                } finally { image.recycle() }
                api.complete(AnnotationRequestFactory.create(api.config.modelName, encoded))
            } else {
                val image = NumberedCollage.encode(candidate.mergeCount, VlmInferencePolicy.PRIMARY_MAX_EDGE) { index, _ ->
                    Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.HSVToColor(floatArrayOf(index * 360f / candidate.mergeCount, 0.7f, 0.85f)))
                    }
                }
                api.complete(AnnotationRequestFactory.createBatch(api.config.modelName, image.base64, candidate.mergeCount))
            }
        }
        try {
            if (candidate.mergeCount == 1) AnnotationContract.parseVlm(response)
            else BatchAnnotationContract.parse(response, candidate.mergeCount)
        } catch (_: Exception) {
            throw IllegalStateException("模型返回的标注不符合要求，请选择支持图片和对应合并张数的 JSON Schema 输出模型")
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        return "连接成功：${api.config.modelName} 已通过 ${candidate.mergeCount} 张图片与结构化输出测试（${elapsedMillis} ms）"
    }
}
