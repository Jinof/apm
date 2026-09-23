package com.jinof.apm

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Uses a generated image and the production contract, never the photo library or database. */
internal object ApiConnectionTest {
    fun run(candidate: InferenceConfig): String {
        val api = ExternalApiClient(candidate, readTimeoutMillis = 60_000)
        val started = System.nanoTime()
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val encoded = try {
            image.eraseColor(Color.rgb(50, 110, 210))
            ByteArrayOutputStream().use { output ->
                check(image.compress(Bitmap.CompressFormat.JPEG, 82, output)) { "无法生成连接测试图片" }
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            image.recycle()
        }
        val response = api.complete(AnnotationRequestFactory.create(api.config.modelName, encoded))
        try {
            AnnotationContract.parseVlm(response)
        } catch (_: Exception) {
            throw IllegalStateException("模型返回的标注不符合要求，请选择支持图片和 JSON Schema 的模型")
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        return "连接成功：${api.config.modelName} 已通过图片与结构化输出测试（${elapsedMillis} ms）"
    }
}
