package com.jinof.apm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ApmTheme {
                ModelSettingsScreen(
                    onNavigate = ::navigateDock,
                )
            }
        }
    }

    private fun navigateDock(destination: DockDestination) {
        if (destination != DockDestination.SETTINGS) {
            openDockDestination(destination)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSettingsScreen(
    onNavigate: (DockDestination) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val initial = remember { store.load() }
    var endpoint by rememberSaveable { mutableStateOf(initial.endpoint) }
    var model by rememberSaveable { mutableStateOf(initial.modelName) }
    var allowRemote by rememberSaveable { mutableStateOf(initial.allowRemote) }
    var endpointError by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("模型设置", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "连接你的 VLM",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = scaffoldPadding.calculateTopPadding() + 12.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(22.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Icon(
                                Icons.Outlined.CloudQueue,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(12.dp).size(28.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "Ollama 视觉模型",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "APM 会先验证地址与授权，再读取用于推理的照片缩略副本。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            )
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("连接", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = {
                            endpoint = it
                            endpointError = null
                        },
                        modifier = Modifier.fillMaxWidth().testTag("endpoint_input"),
                        singleLine = true,
                        label = { Text("Ollama 地址") },
                        placeholder = { Text("http://192.0.2.2:11434") },
                        supportingText = { Text("127.0.0.1 表示 Android 设备本身") },
                        isError = endpointError != null,
                        errorMessage = endpointError,
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth().testTag("model_input"),
                        singleLine = true,
                        label = { Text("模型名称") },
                        placeholder = { Text("qwen3-vl:4b") },
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("本地人物与宠物身份", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "姓名只来自手机本地 embedding 匹配。VLM 只看到 P1、PET1 等匿名编号，不接收人物或宠物姓名。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onNavigate(DockDestination.PEOPLE) },
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("open_identity_settings"),
                    ) {
                        Icon(Icons.Outlined.Person, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("管理本地人物与宠物识别")
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { allowRemote = !allowRemote }
                        .testTag("remote_consent"),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "允许局域网 / 远程推理",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "开启后，带匿名 P/PET 编号的缩小照片副本会发送到上方地址；人物与宠物姓名、embedding 和匹配分数永不发送。Agent 搜索时只发送请求文字和本地身份标签。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.76f),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = allowRemote,
                            onCheckedChange = { allowRemote = it },
                        )
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Ollama API 默认没有鉴权。局域网地址只应用于可信网络，不要映射到公网。保存设置不会发送任何照片。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        try {
                            val saved = store.save(
                                InferenceConfig(
                                    endpoint = endpoint,
                                    modelName = model,
                                    allowRemote = allowRemote,
                                ),
                            )
                            endpoint = saved.endpoint
                            model = saved.modelName
                            allowRemote = saved.allowRemote
                            endpointError = null
                            scope.launch { snackbar.showSnackbar("模型设置已保存") }
                        } catch (error: Exception) {
                            endpointError = error.message ?: "配置不合法"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("save_settings"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("保存设置", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    singleLine: Boolean,
    label: @Composable (() -> Unit),
    placeholder: @Composable (() -> Unit),
    supportingText: (@Composable (() -> Unit))? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    leadingIcon: (@Composable (() -> Unit))? = null,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        label = label,
        placeholder = placeholder,
        supportingText = if (errorMessage != null) {
            { Text(errorMessage) }
        } else {
            supportingText
        },
        isError = isError,
        leadingIcon = leadingIcon,
        shape = MaterialTheme.shapes.medium,
    )
}
