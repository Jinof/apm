# APM — 照片管理与外部 AI 接口

APM 提供 Android 相册和 Python 命令行。照片目录、标注和搜索索引保存在本地；模型推理通过用户配置的外部 API 完成。仓库不包含模型权重、推理运行库、模型下载或服务部署代码。

## Roadmap 0.0.5

- [x] API 设置按模型卡片展示。
- [x] 功能类别：**多模态模型**。
- [x] 用途：**生成描述、标签、识别图片文字，规划自然语言搜索**。
- [x] 保留地址、模型、密钥、传输许可、推荐模型和“测试连接”。
- [x] 移除照片重复／相似检测及人物、宠物身份识别，后续再实现。

## 当前功能

详见 [当前功能与数据边界](docs/current-features.md)。

模型选型与速度、效果对比见 [xspark 多模态模型实测报告](docs/benchmarks/xspark-vlm-2026-09-23/README.md)。

## Android

要求 Android 10（API 29）及以上。选择照片后即可浏览，无需先配置模型。

在“设置”的多模态模型卡片中填写：

| 配置 | 说明 |
|---|---|
| API 基础地址 | 含版本或代理路径，例如 `https://api.example.com/v1` |
| 模型名称 | 服务实际提供、支持图片输入与 JSON Schema 输出的模型 ID |
| API 密钥 | 可选 Bearer 密钥；无鉴权的私有服务可留空 |
| 传输许可 | 明确允许将照片缩略图和搜索请求发送到配置的服务 |

接口为 `POST <基础地址>/chat/completions`。新安装不预填服务或模型。旧版明确保存的 Ollama 配置迁移到同一主机的兼容 `/v1` 路径，保留原模型名和许可。配置和密钥使用 Android Keystore 加密，存于禁止备份目录。

保存设置不会发起请求。标注只发送内存缩略图，不附加身份候选或匿名框；Agent 只发送用户输入与搜索工具约定，不上传照片或本地身份目录。关闭许可后不能开始新任务；运行中的任务继续使用启动时的配置。

“测试连接”使用当前未保存的配置，发送一张生成的 64×64 小图片并验证完整标注结构，可能产生 API 费用。测试不读取相册、不保存配置、不写入索引；修改配置后旧测试结果清除。

设置页推荐模型为 [Qwen3.6-35B-A3B NVFP4](https://huggingface.co/nvidia/Qwen3.6-35B-A3B-NVFP4)，点击“填入”使用模型 ID `qwen3.6-35b-a3b`。该模型已于 2026-09-23 接入 xspark 的 new-api：`http://192.168.2.10:3000/v1`，图片标注、OCR、连接测试与搜索规划均已通过实际调用，详见 [部署记录](docs/xspark-qwen3.6-35b-a3b-nvfp4.md)。此前完整标注基准中位数为 10.52 秒，计数与 OCR 表现较好，但仍有空间关系错误，详见 [实测报告](docs/benchmarks/xspark-vlm-2026-09-23/README.md)。其他兼容服务使用其实际模型 ID。点击推荐仅修改模型草稿，不自动保存或切换已有配置。详细协议见 [外部 API 契约](docs/external-apis.md)。

## Python CLI

要求 Python 3.11+，运行时只用标准库。

```bash
uv tool install -e .
apm init
apm scan ~/Pictures

export APM_API_URL=https://api.example.com/v1
export APM_MODEL=your-vision-model
# 在本机环境中设置 APM_API_KEY；无鉴权的私有服务可不设置。
apm tag --allow-remote --limit 20
apm search 海边
```

地址和模型也可通过 `apm tag --api-url ... --model ...` 提供。非回环地址必须传入 `--allow-remote`。CLI 直接发送照片内容，支持 JPEG、PNG、WebP 和 GIF；其他格式仍可扫描和搜索。

默认数据库为 `~/.apm/apm.sqlite3`，可用 `--db` / `APM_DB` 修改。标注保留模型、提供方和提示词版本，重新标注追加记录，失败不覆盖已有结果。人物／宠物候选名称参数已移除。

已有索引也可通过 [apm-search](skills/apm-search/SKILL.md) 做只读复合检索：

```bash
python3 skills/apm-search/scripts/search.py --query 天黑 --query 海边 --match all
```

## 构建与验证

```bash
make check PROMISE_CLI=/path/to/promise-cli
```

`android-check` 使用全新的 `/private/tmp/apm-build-<UUID>` 目录，执行 JVM 测试、lint、主 APK 和测试 APK 构建，并检查 APK 不包含模型权重或推理运行库。可指定尚不存在的 `APM_BUILD_DIR=/private/tmp/<unique-name>`。仪器测试仅在模拟器上运行，使用本地 mock API。

Android 版本为 **0.0.6（8）**。仓库唯一交付 APK 仍为 `android/APM-0.0.2-debug.apk`，文件名按 [AGENTS.md](AGENTS.md) 保持不变，通过验证后才更新。真实手机只对已安装的 `com.jinof.apm` 执行 `adb install -r` 保留数据更新，安装前后核对版本与数据库。

下载与变更见 [0.0.6 发布说明](docs/release-0.0.6.md) 和 [GitHub Release](https://github.com/Jinof/apm/releases/tag/v0.0.6)。

本版检查结果见 [0.0.6 验收记录](docs/roadmap-0.0.6-validation.md)。此前版本见 [0.0.5 验收记录](docs/roadmap-0.0.5-validation.md)。此前的 [API 设置验收](docs/api-connections-validation.md) 和 [API-only 精简验收](docs/api-only-validation.md) 是历史记录，包含本版已移除的功能。


## Roadmap 0.0.6

- [x] 重新打开应用时清空“标注所选”的选择，显示 0 并禁用按钮。
- [x] 首页返回退出、划掉任务或进程重启后开始新会话；切后台、旋转、页面切换和查看器返回保留本次选择。
- [x] 选图完成替换本次范围；取消选图不改变已有选择。清空不影响照片授权、目录、历史标注及 API 设置。
- [x] 已标注照片的缩略图右下角显示醒目的绿色勾，支持相册缩略图、详情和本地搜索结果。
- [x] 正在标注的图片右下角显示旋转圆圈，结束后恢复已标注或未标注状态。
- [x] 全量标注改为任务卡片，展示当前图片、阶段日志、完成／失败／跳过数量、耗时与 ETA。
