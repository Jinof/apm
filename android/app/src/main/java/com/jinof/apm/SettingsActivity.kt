package com.jinof.apm

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ApmTheme { ModelSettingsScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSettingsScreen(isActive: Boolean = true) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val editor = remember(context) {
        ViewModelProvider(context.settingsActivity())[ApiSettingsEditor::class.java].also { it.initialize(store) }
    }
    var status by remember { mutableStateOf("") }
    var discardChanges by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ApiProfile?>(null) }
    fun returnToList() {
        if (editor.apiTesting) return
        if (editor.dirty) discardChanges = true else { editor.closeEditor(); status = "" }
    }
    // A retained editor must only handle Back while its Dock page is visible.
    BackHandler(enabled = isActive && editor.editing) { returnToList() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (!editor.editing) "API 设置" else if (
                    editor.profiles.profiles.none { it.id == editor.draft.id }
                ) "新增 API" else "编辑 API") },
                navigationIcon = {
                    if (editor.editing) IconButton(enabled = !editor.apiTesting, onClick = ::returnToList,
                        modifier = Modifier.testTag("api_editor_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回 API 列表")
                    }
                },
            )
        },
    ) { padding ->
        if (editor.editing) {
            ApiProfileForm(editor, status, Modifier.padding(padding), onSave = {
                status = try {
                    editor.save(store)
                    if (editor.draft.id != editor.profiles.activeProfileId) "配置已保存，点击「启用」后生效"
                    else "API 设置已保存"
                } catch (_: Exception) { "保存失败，请检查配置名称、API 地址、模型和密钥" }
            }, onDelete = { deleting = editor.draft })
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Button(enabled = !editor.apiTesting, onClick = { status = ""; editor.add() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                        .testTag("add_api_profile")) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("新增 API")
                }
                Text("当前使用：${editor.profiles.active.name}", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp).testTag("active_api_profile"))
                Text(status, modifier = Modifier.padding(horizontal = 20.dp).testTag("api_settings_status"))
                LazyColumn(Modifier.fillMaxSize().testTag("api_settings_list"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    items(editor.profiles.profiles, key = { it.id }) { profile ->
                        ApiProfileRow(profile, profile.id == editor.profiles.activeProfileId, !editor.apiTesting,
                            onEdit = { status = ""; editor.open(profile) },
                            onActivate = {
                                status = try {
                                    editor.activate(store, profile.id)
                                    if (profile.config.allowRemote) "已启用 ${profile.name}，下次任务生效"
                                    else "已选择 ${profile.name}；尚未允许传输，请在编辑中设置"
                                } catch (_: Exception) { "启用失败，请重试" }
                            })
                        HorizontalDivider()
                    }
                    item {
                        Text("每个 API 独立保存模型、密钥和传输许可。启用后用于下一次任务，正在执行的任务继续使用原配置。",
                            modifier = Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (discardChanges) AlertDialog(
        onDismissRequest = { discardChanges = false }, title = { Text("有未保存的修改") },
        text = { Text("返回列表会放弃当前草稿，已保存的配置不受影响。") },
        confirmButton = { TextButton(onClick = {
            discardChanges = false; editor.closeEditor(); status = ""
        }, modifier = Modifier.testTag("discard_profile_changes")) { Text("放弃修改并返回") } },
        dismissButton = { TextButton(onClick = { discardChanges = false },
            modifier = Modifier.testTag("keep_profile_changes")) { Text("继续编辑") } },
    )
    deleting?.let { profile -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text("删除 ${profile.name}？") },
        text = { Text("将删除此配置及其密钥；照片和历史标注会保留。") },
        confirmButton = { TextButton(onClick = {
            deleting = null
            status = try { editor.delete(store, profile.id); "配置已删除" }
            catch (_: Exception) { "删除失败，请先启用其他配置" }
        }, modifier = Modifier.testTag("confirm_delete_profile")) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
    ) }
}

@Composable
private fun ApiProfileRow(profile: ApiProfile, active: Boolean, enabled: Boolean, onEdit: () -> Unit, onActivate: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("api_profile_row_${profile.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("api_profile_name_${profile.id}"))
            Text(profile.config.endpoint.ifBlank { "尚未填写地址" }, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("模型：${profile.config.modelName.ifBlank { "未填写" }}", style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (profile.config.allowRemote) "已允许传输" else "尚未允许传输", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = enabled, onClick = onEdit, modifier = Modifier.testTag("edit_profile_${profile.id}")) { Text("编辑") }
            OutlinedButton(enabled = enabled && !active, onClick = onActivate,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("activate_profile_${profile.id}")) { Text(if (active) "已启用" else "启用") }
        }
    }
}

@Composable
private fun ApiProfileForm(editor: ApiSettingsEditor, status: String, modifier: Modifier, onSave: () -> Unit, onDelete: () -> Unit) {
    val draft = editor.draft
    val config = draft.config
    val enabled = !editor.apiTesting
    Column(modifier.fillMaxSize().imePadding().testTag("api_profile_editor").verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("正在编辑：${draft.name}${if (editor.dirty) "（未保存）" else ""}", modifier = Modifier.testTag("editing_api_profile"))
        OutlinedTextField(value = draft.name, onValueChange = editor::rename, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("profile_name_input"), singleLine = true,
            label = { Text("配置名称") }, supportingText = { Text("例如：家中、外出。最多 60 个字符。") })
        OutlinedTextField(value = config.endpoint, onValueChange = { editor.edit(config.copy(endpoint = it)) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("endpoint_input"), singleLine = true,
            label = { Text("API 基础地址") }, placeholder = { Text("https://api.example.com/v1") })
        OutlinedTextField(value = config.modelName, onValueChange = { editor.edit(config.copy(modelName = it)) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("model_input"), singleLine = true, label = { Text("模型名称") })
        OutlinedTextField(value = config.apiKey, onValueChange = { editor.edit(config.copy(apiKey = it)) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("api_key_input"), singleLine = true,
            label = { Text("API 密钥") }, supportingText = { Text("无鉴权的私有 API 可留空；密钥仅加密保存在本机。") },
            visualTransformation = PasswordVisualTransformation())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = config.allowRemote, onCheckedChange = { editor.edit(config.copy(allowRemote = it)) },
                enabled = enabled, modifier = Modifier.testTag("remote_consent"))
            Text("允许将照片和搜索请求发送到此 API", modifier = Modifier.padding(start = 8.dp))
        }
        Button(enabled = enabled, onClick = onSave, modifier = Modifier.fillMaxWidth().testTag("save_settings")) { Text("保存 API 设置") }
        Text(status, modifier = Modifier.testTag("api_settings_status"))
        OutlinedButton(enabled = enabled, onClick = editor::testConnection,
            modifier = Modifier.fillMaxWidth().testTag("test_model_api")) { Text(if (editor.apiTesting) "连接中…" else "测试连接") }
        Text("使用当前填写的配置发送一张生成的小图片，验证图片输入与结构化输出，可能产生 API 费用；不读取相册、不保存设置。",
            style = MaterialTheme.typography.bodySmall)
        Text(editor.apiTestStatus, modifier = Modifier.testTag("model_api_test_status"))
        ElevatedCard(Modifier.fillMaxWidth().testTag("multimodal_model_card")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("功能类别：多模态模型", style = MaterialTheme.typography.titleMedium)
                Text("用途：生成描述、标签、识别图片文字，规划自然语言搜索")
                Text("推荐模型", style = MaterialTheme.typography.titleSmall)
                ModelRecommendation("qwen3.6-35b-a3b", "Qwen3.6-35B-A3B NVFP4 · xspark 实测",
                    "https://huggingface.co/nvidia/Qwen3.6-35B-A3B-NVFP4", enabled) { editor.edit(config.copy(modelName = it)) }
                Text("2026-09-23 在 xspark 实测完整照片标注约 10.52 秒，支持图片、OCR 与结构化输出。请确认 API 已提供该模型；其他服务使用其实际模型 ID。填入后仍需保存。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("家中使用 xspark 内网地址，例如 http://192.168.2.10:3000/v1；外出填写自己的 Tailscale Funnel HTTPS 地址。手机上的 localhost 指手机本身。保存和启用不会发起请求。",
            style = MaterialTheme.typography.bodySmall)
        if (editor.profiles.profiles.any { it.id == draft.id }) {
            TextButton(enabled = enabled && draft.id != editor.profiles.activeProfileId,
                onClick = onDelete, modifier = Modifier.testTag("delete_api_profile")) { Text("删除此配置") }
            if (draft.id == editor.profiles.activeProfileId) Text("如需删除，请先启用其他配置。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private tailrec fun Context.settingsActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.settingsActivity()
    else -> error("API settings require an activity")
}

@Composable
private fun ModelRecommendation(model: String, description: String, documentationUrl: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(model, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(enabled = enabled, onClick = { onSelect(model) }, modifier = Modifier.testTag("recommend_$model")) { Text("填入") }
        TextButton(onClick = { uriHandler.openUri(documentationUrl) }) { Text("文档") }
    }
}
