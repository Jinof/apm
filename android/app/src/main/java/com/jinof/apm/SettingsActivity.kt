package com.jinof.apm

import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
    val editor = remember(context) {
        ViewModelProvider(context.settingsActivity())[ApiSettingsEditor::class.java].also { it.initialize(store) }
    }
    val draft = editor.draft
    val config = draft.config
    var status by remember { mutableStateOf("") }
    val apiTesting = editor.apiTesting
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var deleting by remember { mutableStateOf<ApiProfile?>(null) }
    fun changeProfile(action: () -> Unit) {
        if (editor.dirty) pendingAction = action else { status = ""; action() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("API 设置") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("api_settings_list")
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("当前使用：${editor.profiles.active.name}", modifier = Modifier.testTag("active_api_profile"),
                style = MaterialTheme.typography.titleMedium)
            Text("每个配置独立保存地址、模型、密钥和传输许可。手动切换后用于下一次标注或搜索，正在执行的任务继续使用原配置。")
            editor.profiles.profiles.forEach { profile ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text(profile.config.endpoint.ifBlank { "尚未填写地址" }, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = !apiTesting, onClick = { changeProfile { editor.select(profile) } },
                                modifier = Modifier.testTag("edit_profile_${profile.id}")) { Text("编辑") }
                            if (profile.id == editor.profiles.activeProfileId) Text("正在使用")
                            else TextButton(enabled = !apiTesting, onClick = {
                                changeProfile {
                                    status = try { editor.activate(store, profile.id); "已切换到 ${profile.name}，下次任务生效" }
                                    catch (_: Exception) { "切换失败，请重试" }
                                }
                            }, modifier = Modifier.testTag("activate_profile_${profile.id}")) { Text("切换使用") }
                        }
                    }
                }
            }
            OutlinedButton(enabled = !apiTesting, onClick = { changeProfile { editor.add() } },
                modifier = Modifier.testTag("add_api_profile")) { Text("添加 API 配置") }
            Text("家中使用 xspark 的内网地址，例如 http://192.168.2.10:3000/v1；外出填写自己的 Tailscale Funnel HTTPS 地址。手机上的 localhost 指手机本身。",
                style = MaterialTheme.typography.bodySmall)
            ElevatedCard(modifier = Modifier.fillMaxWidth().testTag("multimodal_model_card")) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("正在编辑：${draft.name}${if (editor.dirty) "（未保存）" else ""}", modifier = Modifier.testTag("editing_api_profile"))
                    OutlinedTextField(value = draft.name, onValueChange = editor::rename, enabled = !apiTesting,
                        modifier = Modifier.fillMaxWidth().testTag("profile_name_input"), singleLine = true,
                        label = { Text("配置名称") }, supportingText = { Text("例如：家中、外出。最多 60 个字符。") })
                    Text("功能类别：多模态模型", style = MaterialTheme.typography.titleLarge)
                    Text("用途：生成描述、标签、识别图片文字，规划自然语言搜索")
                    Text("连接支持图片和结构化输出的 OpenAI 兼容 API。标注发送照片缩略图；搜索发送请求文字。保存设置不会发起请求，修改在下次任务开始时生效。")
                    OutlinedTextField(value = config.endpoint, onValueChange = { editor.edit(config.copy(endpoint = it)) },
                        enabled = !apiTesting,
                        modifier = Modifier.fillMaxWidth().testTag("endpoint_input"), singleLine = true,
                        label = { Text("API 基础地址") }, placeholder = { Text("https://api.example.com/v1") })
                    OutlinedTextField(value = config.modelName, onValueChange = { editor.edit(config.copy(modelName = it)) },
                        enabled = !apiTesting,
                        modifier = Modifier.fillMaxWidth().testTag("model_input"), singleLine = true,
                        label = { Text("模型名称") })
                    Text("推荐模型", style = MaterialTheme.typography.titleMedium)
                    ModelRecommendation("qwen3.6-35b-a3b", "Qwen3.6-35B-A3B NVFP4 · xspark 实测",
                        "https://huggingface.co/nvidia/Qwen3.6-35B-A3B-NVFP4", !apiTesting) { editor.edit(config.copy(modelName = it)) }
                    Text("2026-09-23 在 xspark 实测完整照片标注约 10.52 秒，支持图片、OCR 与结构化输出。请确认 API 已提供该模型；其他服务使用其实际模型 ID。填入后仍需保存。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = config.apiKey, onValueChange = { editor.edit(config.copy(apiKey = it)) },
                        enabled = !apiTesting,
                        modifier = Modifier.fillMaxWidth().testTag("api_key_input"), singleLine = true,
                        label = { Text("API 密钥") }, supportingText = { Text("无鉴权的私有 API 可留空；密钥仅加密保存在本机。") },
                        visualTransformation = PasswordVisualTransformation())
                    ConsentSwitch(config.allowRemote, { editor.edit(config.copy(allowRemote = it)) }, "允许将照片和搜索请求发送到此 API", "remote_consent", !apiTesting)
                    Button(enabled = !apiTesting, onClick = {
                        status = try {
                            editor.save(store)
                            if (draft.id != editor.profiles.activeProfileId) "配置已保存，点击「切换使用」后生效"
                            else if (editor.draft.config.allowRemote) "API 设置已保存" else "API 已禁用，原有照片和索引已保留"
                        } catch (_: Exception) { "保存失败，请检查配置名称、API 地址、模型和密钥" }
                    }, modifier = Modifier.fillMaxWidth().testTag("save_settings")) { Text("保存 API 设置") }
                    Text(status, modifier = Modifier.testTag("api_settings_status"))
                    OutlinedButton(enabled = !apiTesting, onClick = editor::testConnection,
                        modifier = Modifier.fillMaxWidth().testTag("test_model_api")) {
                        Text(if (apiTesting) "连接中…" else "测试连接")
                    }
                    Text("使用当前填写的配置发送一张生成的小图片，验证模型可用性、图片输入与结构化输出，可能产生 API 费用；不读取相册、不保存设置。",
                        style = MaterialTheme.typography.bodySmall)
                    Text(editor.apiTestStatus, modifier = Modifier.testTag("model_api_test_status"))
                    if (editor.profiles.profiles.any { it.id == draft.id }) {
                        TextButton(enabled = !apiTesting && draft.id != editor.profiles.activeProfileId,
                            onClick = { deleting = draft }, modifier = Modifier.testTag("delete_api_profile")) { Text("删除此配置") }
                        if (draft.id == editor.profiles.activeProfileId) Text("如需删除，请先切换到其他配置。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (pendingAction != null) AlertDialog(
        onDismissRequest = { pendingAction = null }, title = { Text("有未保存的修改") },
        text = { Text("继续会放弃当前草稿，已保存的配置不受影响。") },
        confirmButton = { TextButton(onClick = {
            val action = pendingAction; pendingAction = null; status = ""; action?.invoke()
        }, modifier = Modifier.testTag("discard_profile_changes")) { Text("放弃修改并继续") } },
        dismissButton = { TextButton(onClick = { pendingAction = null }, modifier = Modifier.testTag("keep_profile_changes")) { Text("继续编辑") } },
    )
    deleting?.let { profile -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text("删除 ${profile.name}？") },
        text = { Text("将删除此配置及其密钥；照片和历史标注会保留。") },
        confirmButton = { TextButton(onClick = {
            deleting = null
            status = try { editor.delete(store, profile.id); "配置已删除" } catch (_: Exception) { "删除失败，请先切换到其他配置" }
        }, modifier = Modifier.testTag("confirm_delete_profile")) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
    ) }
}

private tailrec fun Context.settingsActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.settingsActivity()
    else -> error("API settings require an activity")
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
