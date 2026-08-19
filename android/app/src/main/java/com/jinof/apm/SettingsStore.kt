package com.jinof.apm

import android.content.Context
import java.time.Instant

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("inference", Context.MODE_PRIVATE)

    fun load(): InferenceConfig = InferenceConfig(
        endpoint = preferences.getString("endpoint", EndpointPolicy.DEFAULT_ENDPOINT)
            ?: EndpointPolicy.DEFAULT_ENDPOINT,
        modelName = preferences.getString("model", "qwen3-vl:4b") ?: "qwen3-vl:4b",
        allowRemote = preferences.getBoolean("allow_remote", false),
    )

    fun loadRecognitionProfile(): RecognitionProfile = RecognitionProfile()

    fun save(
        candidate: InferenceConfig,
        @Suppress("UNUSED_PARAMETER") recognitionProfile: RecognitionProfile = RecognitionProfile(),
    ): InferenceConfig {
        val validated = EndpointPolicy.validate(candidate)
        preferences.edit()
            .putString("endpoint", validated.endpoint)
            .putString("model", validated.modelName)
            .putBoolean("allow_remote", validated.allowRemote)
            .remove("person_names")
            .remove("pet_names")
            .putString("updated_at", Instant.now().toString())
            .apply()
        return validated
    }
}
