package com.jinof.apm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.util.UUID
import kotlinx.coroutines.*

/** Draft credentials survive rotation only in memory, never in saved instance state. */
class ApiSettingsEditor : ViewModel() {
    var profiles by mutableStateOf(ApiProfiles.single())
        private set
    var draft by mutableStateOf(profiles.active)
        private set
    var apiTesting by mutableStateOf(false)
        private set
    var apiTestStatus by mutableStateOf("")
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialized = false
    val dirty get() = profiles.profiles.find { it.id == draft.id } != draft

    fun initialize(store: SettingsStore) {
        if (!initialized) {
            profiles = store.loadProfiles()
            draft = profiles.active
            initialized = true
        }
    }
    fun rename(name: String) { select(draft.copy(name = name)) }
    fun edit(config: InferenceConfig) { select(draft.copy(config = config)) }
    fun select(profile: ApiProfile) { draft = profile; apiTestStatus = "" }
    fun add() { select(ApiProfile(UUID.randomUUID().toString(), "新配置", InferenceConfig())) }
    fun save(store: SettingsStore) {
        profiles = store.saveProfile(draft.id, draft.name, draft.config)
        draft = profiles.profiles.single { it.id == draft.id }
    }
    fun activate(store: SettingsStore, id: String) {
        profiles = store.activateProfile(id)
        select(profiles.active)
    }
    fun delete(store: SettingsStore, id: String) {
        profiles = store.deleteProfile(id)
        select(profiles.active)
    }
    fun testConnection() {
        if (apiTesting) return
        val candidate = draft.config
        apiTesting = true
        apiTestStatus = ""
        scope.launch {
            try {
                apiTestStatus = withContext(Dispatchers.IO) {
                    runCatching { ApiConnectionTest.run(candidate) }
                        .getOrElse { "测试失败：${it.message ?: "无法连接 API"}" }
                }
            } finally { apiTesting = false }
        }
    }
    override fun onCleared() { scope.cancel() }

}
