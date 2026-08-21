# APM — 本地优先的 AI 照片管理器

APM 是一个 Android Native App 加本地命令行内核。Android 端只读扫描用户授权的 `MediaStore` 照片；两端都用多模态模型生成中文描述、结构化标签和图片内文字，再把这些数据写入设备内 SQLite 索引。它不修改、移动或删除原照片。

当前 MVP 提供同一个闭环：

1. `scan`：按原始字节的 SHA-256 去重，同时保留同一照片的多个路径；
2. `tag` / “标注未处理”：默认通过回环地址调用本地 Ollama，使用 JSON Schema 验证模型结果并保留模型/提示词版本；
3. `search` / App 搜索框：搜索最新描述、天色、天空、物体与数量、人物外观呈现、动作、场景、天气、本地匹配的人物或宠物姓名、通用标签和可见文字，只返回当前仍可访问的照片；
4. `Agent 搜索`：最小化 Agent 把自然语言规划成 1–4 次只读 `search_photos` 调用，按全部条件或任一条件合并结果，不具备扫描、标注、移动或删除工具。
5. `相似照片`：通过一个“检查相似”入口选择增量、用户自定义最近小时/天/周或全量检查；DINOv2 ViT-S/14 registers 全图 class token 识别同场景，固定 4×4 patch 池化保留构图位置，拍摄时间只辅助判定连拍；人脸、宠物与通用物体裁剪 embedding 补充主体相似度。VLM 标签只在排序完成后生成解释，不参与分数或顺序。
6. `照片墙与原图查看器`：独立于搜索与标注状态，展示全部当前已授权照片；按设备时区中的真实拍摄日分组，可切换三列方形缩略图或全宽明细，并用月历热力图显示每日照片数和筛选某一天。点照片会留在 APM 内直接解码完整原始分辨率，不调用缩略图或目标尺寸降采样；可双指缩放到至少原始像素 1:1、拖动并切换上一张或下一张。拍摄时间缺失时只进入“拍摄时间未记录”，不使用修改或标注时间代替。

系统边界和验收场景以 [`apm.promise`](apm.promise) 为唯一真相来源。

## 为什么首选 Ollama + Qwen3-VL

首版默认模型是 `qwen3-vl:4b`。它约 3.3GB，适合作为响应速度与图片理解质量之间的起点；18GB Apple Silicon 也可以把 `APM_MODEL` 改为 `qwen3-vl:8b`。Ollama 的 Vision API 支持图片输入，Structured Outputs 支持直接传入 JSON Schema，恰好能避免把自由文本当成可信索引数据。

- [Qwen3-VL 模型与尺寸](https://ollama.com/library/qwen3-vl)
- [Ollama Vision](https://docs.ollama.com/capabilities/vision)
- [Ollama Structured Outputs](https://docs.ollama.com/capabilities/structured-outputs)

## 快速开始

### Android Native App

要求 Android 10（API 29）或更高版本。工程位于 `android/`，使用 Kotlin、Jetpack Compose Material 3、原生 `Activity` / `MediaStore` / `SQLiteOpenHelper`。

```bash
cd android
APM_BUILD_DIR="/private/tmp/apm-build-$(date +%Y%m%d-%H%M%S)"
test ! -e "$APM_BUILD_DIR"
./gradlew -PapmIsolatedBuildDir="$APM_BUILD_DIR" testDebugUnitTest lintDebug assembleDebug
adb install -r "$APM_BUILD_DIR/outputs/apk/debug/app-debug.apk"
```

首次打开后：

1. 点击“选择照片”，授予全部照片或 Android 14 的选定照片权限；扫描只通过 `ContentResolver` 读取和计算 SHA-256。使用“选定照片”时，每次点击都会重新打开系统选择器，可以持续增加或调整照片；
2. 所有顶层页面位于同一个平滑横向分页容器中，共享固定在底部的 Dock：最左侧的“相册”是首页，随后依次是“热力图”“人”“Agent”和“设置”；页面会跟随手指横向移动，松手后按速度平滑吸附且单次手势最多前进一页，连续点击 Dock 时会立即转向最后一次选择，不排队播放过时动画，也不再弹出新的顶层页面。“热力图”“人”“Agent”和“设置”不显示重复的左上返回箭头，可通过左右滑动、Dock 或系统返回键回到“相册”。默认 `http://127.0.0.1:11434` 指手机本身；使用 Mac mini VLM 时填写私网地址并勾选照片发送授权；
3. 从底部 Dock 的“人”打开“本地身份识别”，选择“人物”或“宠物”，再选择参考照片、点选一个检测框并命名。人物使用 YuNet + SFace；猫狗使用 SSD MobileNet V1 + MobileNetV3。检测、裁剪和 embedding 均由不带网络上报的 LiteRT Interpreter 在手机本地运行，同名可增加不同角度或光照的参考模板；
4. 新照片会在本地按 cosine 相似度匹配。人物阈值为 `0.55`、歧义间隔为 `0.05`；宠物采用更保守的同物种阈值 `0.90`、歧义间隔 `0.04`。不满足条件时明确保存为“未知”，不会强行命名；
5. 标注前，App 在内存缩略图上为本地检测主体绘制 `P1`、`P2`、`PET1` 等匿名框。VLM 只负责描述、物体、数量、动作和场景，并返回匿名编号；真实人物/宠物姓名不进入 VLM 请求，最终描述由手机按当前本地匹配动态替换；
6. 先通过“选择照片”授权待处理照片，再选择“标注所选”或“全量标注”。一次明确操作会处理该范围内全部待处理照片，不设隐藏的 20 张上限；App 首次只生成最长边 1024px、JPEG 质量 82 的内存缩略副本，并为 Ollama 请求 8192-token 上下文；仅在服务端明确报告上下文超限时再以最长边 768px 重试一次，绝不编码或写回原图；
7. 授权照片后，照片墙会立即显示全部当前可访问照片，不要求先标注。可在“缩略图”和“明细”间切换；年度热力图把 Q1 到 Q4 纵向排满屏幕，每个季度的三个月横向并排，年份与日/周/月粒度位于同一条紧凑控制栏中；点选有照片的日期、周或月可筛选精确范围。点任一照片会在 APM 的只读全屏查看器中按原始宽高完整解码，缩放上限按图片和屏幕动态计算，至少为 5 倍且足以达到原始像素 1:1；受限拖动和相邻照片导航均不依赖外部看图应用；
8. 标注成功前，搜索框和搜索建议保持禁用且为空；标注后建议只从实际标注及本地人物匹配生成。可搜索“天黑”“两只狗”“女人”“跑步”“海边”或已识别名称；
9. 在“相似照片”区域点击“检查相似”：增量检查只处理缺少当前流水线特征的新增或未完成照片，近期检查按用户输入的小时/天/周重新处理真实拍摄时间位于范围内的照片，全量检查重新处理全部当前已授权照片。近期范围不会用修改时间猜测缺失的拍摄时间；完成后从照片卡片点击“查看相似照片”。结果会分别显示全图、4×4 构图、主体相似度和拍摄间隔；时间接近但视觉不同的照片不会被判为连拍；
10. 打开“Agent 搜索”，用自然语言描述复合条件。规划阶段只发送请求文本和已注册的本地人物/宠物标签，不发送照片、embedding、匹配分数或现有标注内容，实际检索在设备内 SQLite 完成。

App 的本地数据库位于应用私有目录，并关闭 Android 备份。参考照片与人物/宠物裁剪不会被保存；数据库只保留归一化 embedding、边界框、模型版本和匹配结果。失去照片权限只会把对应 URI 标记为当前不可访问，不会推断原图已被删除。非回环 URL 在用户明确勾选授权之前无法保存，授权检查发生在读取上传图片之前。第三方模型来源、固定版本与校验和见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

### DINOv2 相似度模型

App 内置公开的 `facebook/dinov2-with-registers-small` 固定 revision `0d9846e56b43a21fa46d7f3f5070f0506a5795a9`。可用以下命令复现 ONNX：

```bash
python3 -m pip install torch transformers onnx onnxscript
python3 scripts/export_dinov2_onnx.py --output /private/tmp/dinov2_vits14_reg.onnx
```

导出脚本会先移除可能保留本机路径的可选调试元数据，再用 ONNX checker 和 ONNX Runtime 比较 `last_hidden_state` 与 PyTorch 输出。只有形状严格为 `[1,261,384]` 且数值误差通过门槛时才生成 SHA-256。当前单文件 ONNX 的 SHA-256 是 `18964f360347671c5313fddeed2617b7e8f212790cfd52a41fcc146562cf9dbd`。

Android 使用 ONNX Runtime 1.24.3 在设备端推理，首次索引前把模型复制到 app-private `noBackupFilesDir` 并校验 SHA-256。输出必须为 `1 class + 4 register + 256 patch = 261` 个 token；排除 class/register 后，16×16 patch 网格按原坐标池化成 4×4。缺失或不兼容时只显示明确错误，不写入任何伪特征。

### Python CLI

要求 Python 3.11+，并在本机安装 [Ollama](https://ollama.com/)。运行时没有第三方 Python 依赖。

```bash
ollama pull qwen3-vl:4b
uv tool install -e .

apm init
apm scan ~/Pictures
apm tag --limit 20
apm search 海边
```

也可以不安装命令，直接从源码运行：

```bash
PYTHONPATH=src python3 -m apm.cli --db /tmp/apm.sqlite3 scan ~/Pictures
PYTHONPATH=src python3 -m apm.cli --db /tmp/apm.sqlite3 tag --limit 20
PYTHONPATH=src python3 -m apm.cli --db /tmp/apm.sqlite3 search 海边
```

默认索引位于 `~/.apm/apm.sqlite3`。可用全局参数 `--db` 或环境变量 `APM_DB` 修改。模型可用 `--model` / `APM_MODEL` 修改。

Python CLI 默认只允许 `localhost`、`127.0.0.1` 或 `::1` 的 Ollama 地址。如果明确使用局域网或远端 Ollama，必须同时提供 `--ollama-url` 和 `--allow-remote`；这会把照片字节发送到该地址。Android App 使用模型设置中的同等显式授权。

CLI 标注也支持重复传入 `--person-name 小明` 与 `--pet-name 旺财`。仓库内的 [`apm-search` SKILL](skills/apm-search/SKILL.md) 允许其他 Agent 对已有 CLI 索引执行严格只读的单条件或复合条件搜索：

```bash
python3 skills/apm-search/scripts/search.py \
  --query 天黑 \
  --query 旺财 \
  --match all
```

## 使用 Mac mini 推理

Ollama API 本身没有鉴权。优先仅监听回环地址并通过 SSH 隧道访问；只有在用户明确授权且网络可信时才监听私有局域网。不要把真实主机名、用户名、地址或授权状态写入仓库，也不要做公网端口映射。通用 LaunchAgent 模板位于 `deploy/macmini/com.jinof.apm-vlm.plist`。

局域网内可以直接调用：

```bash
apm tag \
  --ollama-url http://192.0.2.2:11434 \
  --allow-remote \
  --model qwen3-vl:4b \
  --limit 20
```

`192.0.2.2` 是文档示例地址；实际使用时请替换为已授权 VLM 主机的当前地址。`--allow-remote` 表示用户确认照片字节会发送到该主机。若离开这个私有局域网，仍可在第一个终端建立加密 SSH 隧道：

```bash
APM_VLM_SSH_HOST="your-mac-mini-host" ./scripts/macmini-vlm-tunnel
```

另一个终端再使用 `--ollama-url http://127.0.0.1:11435`，此时不需要 `--allow-remote`。可用 `APM_VLM_LOCAL_PORT` 修改隧道端口。

## 验证

```bash
make check PROMISE_CLI=/path/to/promise-cli
```

代码测试使用 Python 标准库、JVM 单元测试、Android 数据库集成测试和确定性假数据，不会读取用户照片。YuNet、SFace、SSD MobileNet V1 与 MobileNetV3 模型已固定校验和并随 App 打包；模型加载测试以及已授权的公开猫照片检测测试只在模拟器运行。Android `assembleDebug` 与 `lintDebug` 也包含在交付门禁中。

## MVP 之后

- 本地 Web/桌面图库与缩略图浏览；
- EXIF 时间、相机与 GPS 元数据索引；
- 人工编辑标签，并与模型标注分开保留来源；
- 后台任务、断点续跑和批处理吞吐统计；
- 用真实私有样本集比较 4B/8B 标注质量后再调整默认模型；
- 用真机私有照片集校准不同机型上的相似度阈值与批处理耗时；
- 经用户同意构建私有评测集，以 FAR/FRR 数据校准不同设备、光照和年龄跨度下的人脸阈值。
