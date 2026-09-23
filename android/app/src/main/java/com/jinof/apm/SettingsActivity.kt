package com.jinof.apm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ApmTheme { ModelSettingsScreen() } }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSettingsScreen() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val initial = remember { store.load() }
    var endpoint by rememberSaveable { mutableStateOf(initial.endpoint) }
    var model by rememberSaveable { mutableStateOf(initial.modelName) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var allowRemote by rememberSaveable { mutableStateOf(initial.allowRemote) }
    var status by remember { mutableStateOf("") }
    var apiTesting by remember { mutableStateOf(false) }
    var apiTestStatus by remember(endpoint, model, apiKey, allowRemote) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("API 设置") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("api_settings_list")
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth().testTag("multimodal_model_card")) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("功能类别：多模态模型", style = MaterialTheme.typography.titleLarge)
                    Text("用途：生成描述、标签、识别图片文字，规划自然语言搜索")
                    Text("连接支持图片和结构化输出的 OpenAI 兼容 API。标注发送照片缩略图；搜索发送请求文字。保存设置不会发起请求，修改在下次任务开始时生效。")
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it },
                        enabled = !apiTesting,
                        modifier = Modifier.fillMaxWidth().testTag("endpoint_input"), singleLine = true,
                        label = { Text("API 基础地址") }, placeholder = { Text("https://api.example.com/v1") })
                    OutlinedTextField(value = model, onValueChange = { model = it },
                        enabled = !apiTesting,
                        modifier = Modifier.fillMaxWidth().testTag("model_input"), singleLine = true,
                        label = { Text("模型名称") })
                    Text("推荐模型", style = MaterialTheme.typography.titleMedium)
                    ModelRecommendation("qwen3.6-35b-a3b", "Qwen3.6-35B-A3B NVFP4 · xspark 实测",
                        "https://huggingface.co/nvidia/Qwen3.6-35B-A3B-NVFP4", !apiTesting) { model = it }
                    Text("2026-09-23 在 xspark 实测完整照片标注约 10.52 秒，支持图片、OCR 与结构化输出。请确认 API 已提供该模型；其他服务使用其实际模型 ID。填入后仍需保存。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                        enabled = !apiTesting,
                        modifier = Modifier.fillMaxWidth().testTag("api_key_input"), singleLine = true,
                        label = { Text("API 密钥") }, supportingText = { Text("无鉴权的私有 API 可留空；密钥仅加密保存在本机。") },
                        visualTransformation = PasswordVisualTransformation())
                    ConsentSwitch(allowRemote, { allowRemote = it }, "允许将照片和搜索请求发送到此 API", "remote_consent", !apiTesting)
                    Button(enabled = !apiTesting, onClick = {
                        status = try {
                            val saved = store.save(InferenceConfig(endpoint, model, allowRemote, apiKey))
                            endpoint = saved.endpoint; model = saved.modelName; apiKey = saved.apiKey
                            if (saved.allowRemote) "API 设置已保存" else "API 已禁用，原有照片和索引已保留"
                        } catch (_: Exception) { "保存失败，请检查 API 地址、模型和密钥" }
                    }, modifier = Modifier.fillMaxWidth().testTag("save_settings")) { Text("保存 API 设置") }
                    Text(status, modifier = Modifier.testTag("api_settings_status"))
                    OutlinedButton(enabled = !apiTesting, onClick = {
                        val candidate = InferenceConfig(endpoint, model, allowRemote, apiKey)
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
                    }, modifier = Modifier.fillMaxWidth().testTag("test_model_api")) {
                        Text(if (apiTesting) "连接中…" else "测试连接")
                    }
                    Text("使用当前填写的配置发送一张生成的小图片，验证模型可用性、图片输入与结构化输出，可能产生 API 费用；不读取相册、不保存设置。",
                        style = MaterialTheme.typography.bodySmall)
                    Text(apiTestStatus, modifier = Modifier.testTag("model_api_test_status"))
                }
            }
        }
    }
}

@Composable
private fun ConsentSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, tag: String, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag))
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ModelRecommendation(model: String, description: String, documentationUrl: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(enabled = enabled, onClick = { onSelect(model) }, modifier = Modifier.testTag("recommend_$model")) { Text("填入") }
        TextButton(onClick = { uriHandler.openUri(documentationUrl) }) { Text("文档") }
    }
}
