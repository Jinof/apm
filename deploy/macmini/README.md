# Mac mini VLM 部署

## 安全边界

Ollama API 默认没有鉴权。优先仅监听回环地址并通过 SSH 隧道访问；只有在用户明确授权且网络可信时才监听私有局域网。不要提交真实主机名、用户名、地址或授权状态，也不要做公网端口映射。

## 部署或更新

在 Mac mini 上安装运行时：

```bash
HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_CLEANUP=1 brew install ollama
```

从本仓库复制并加载 LaunchAgent：

```bash
export APM_VLM_SSH_HOST="your-mac-mini-host"
scp deploy/macmini/com.jinof.apm-vlm.plist \
  "${APM_VLM_SSH_HOST}:~/Library/LaunchAgents/com.jinof.apm-vlm.plist"

ssh "$APM_VLM_SSH_HOST" 'launchctl bootstrap gui/$(id -u) \
  ~/Library/LaunchAgents/com.jinof.apm-vlm.plist'
```

如果标签已加载，更新后使用：

```bash
ssh "$APM_VLM_SSH_HOST" 'launchctl kickstart -k gui/$(id -u)/com.jinof.apm-vlm'
```

下载模型：

```bash
ssh "$APM_VLM_SSH_HOST" 'PATH=/opt/homebrew/bin:$PATH \
  OLLAMA_HOST=http://127.0.0.1:11434 ollama pull qwen3-vl:4b'
```

## 验证

```bash
ssh "$APM_VLM_SSH_HOST" 'launchctl print gui/$(id -u)/com.jinof.apm-vlm'
ssh "$APM_VLM_SSH_HOST" 'curl --noproxy "*" http://127.0.0.1:11434/api/tags'
```

完整验收还必须从 APM 运行一次真实 `scan -> tag -> search`，不能只用模型列表代替图片推理。

需要从其他网络访问时，设置 `APM_VLM_SSH_HOST` 后使用 `scripts/macmini-vlm-tunnel`；不要把 Ollama 直接暴露到公网。
