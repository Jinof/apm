# Roadmap 0.0.5 验收记录

日期：2026-09-22。Android 包名 `com.jinof.apm`，版本 `0.0.5`，versionCode `7`。

## 交付内容

- API 设置只保留一个多模态模型卡片，显示功能类别和用途，包含地址、模型、密钥、许可、推荐、保存与测试连接。
- 底部保留相册、热力图、Agent、设置四页；移除重复／相似检测、人物／宠物注册和识别、视觉 API 配置及客户端。
- 新标注直接发送缩略图，不再检测主体、绘制匿名框或传递身份候选；Agent 不再上传本地身份目录。
- Python CLI 同步移除人物／宠物候选参数；历史已存名称仍可检索。
- SQLite 继续使用 schema 8，保留旧身份、模板、特征、标注和加密配置。旧标注只做读取兼容，不再运行识别或重新匹配。

## 验证结果

- Promise format、lint、check：通过，0 警告。
- Python：23 项通过；JVM：40 项通过；Android API 35 模拟器：22 项通过，共 85 项。
- Android lint：0 错误、8 警告。
- API 测试覆盖未保存配置、生成测试图、鉴权失败、无效结构、许可、推荐填入和编辑后清除结果。
- 真实 UI 标注流程仅调用 Chat Completions；后续 401 失败不覆盖此前标注。
- schema 7／8 夹具逐行核对旧身份、人物与宠物模板、观察记录、全图向量、主体特征、照片和标注；允许 v7→v8 移除既有废弃网格列，其余字段保持一致。
- 旧版姓名字段及匿名编号标注在相册和搜索中保持可读；导航动画、设置草稿、日／周／月热力图及原始分辨率查看通过。
- 主 APK、测试 APK 均无模型权重、推理运行库和已移除的视觉客户端／管理 Activity。
- 已人工查看模拟器设置卡片及四项底部导航。测试使用生成图片与本地 mock 服务，未验证 xspark 的实际多模态 API 可用性或模型质量。

## 2026-09-22 首次交付与复核证据

唯一交付件：[APM-0.0.2-debug.apk](../android/APM-0.0.2-debug.apk)。文件名按仓库约定保留，内部版本为 0.0.5。

- 字节数：`54764974`
- SHA-256：`74781f97da6c496cd8152093073124b927578d5c1d6f667421eccc517da2d619`
- 隔离原件：`/private/tmp/apm-roadmap-20260922-f/outputs/apk/debug/app-debug.apk`
- 完整检查日志：`/private/tmp/apm-roadmap-check-f.log`
- 模拟器测试日志：`/private/tmp/apm-roadmap-instrument-f.log`
- UI 截图：`/private/tmp/apm-roadmap-settings-ready.png`、`/private/tmp/apm-roadmap-settings-actions.png`

复现构建：

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
make check PYTHON=/opt/homebrew/bin/python3.13 \
  PROMISE_CLI='/opt/homebrew/bin/python3.13 /Users/jinof/.codex/skills/promise/scripts/promise-cli' \
  APM_BUILD_DIR=/private/tmp/<尚不存在的唯一目录>
```

## 真机安装与数据保留

2026-09-22 22:57:38，用户重新连接小米 23127PN0CC（序列号 `设备序列号已省略`）后，确认设备已安装 `com.jinof.apm` 0.0.4（6）及私有数据库，使用 `adb install -r` 更新到 **0.0.5（7）**，返回 `Success`。未执行卸载、数据清理或真机仪器测试。

- 实际安装 `base.apk` 的 SHA-256 与上文交付件完全一致。
- 首次安装时间保持 `2026-08-13 20:50:20`。
- 安装前、安装后及启动后的 schema 均为 8，SQLite `quick_check` 均为 `ok`。
- 所有业务表逐行哈希一致：7,767 条照片内容、7,775 条位置、3 条标注、1 个人物身份、2 个模板，以及旧人脸、宠物、主体和视觉特征记录完整保留。
- 加密配置文件及已记录的旧模型文件哈希一致。
- 新版 `MainActivity` 正常启动，当前应用进程无崩溃记录；真机截图确认多模态模型卡片、原有 API 配置和四项底部导航正常显示。

安装前后证据保存在权限受限目录 `/private/tmp/apm-roadmap-device-f8de61b248f6`：`before/evidence.json`、`installed/evidence.json`、`launched/evidence.json`、`installed-apk-sha256.txt`、`launch-verification.json` 和 `device-settings.png`。本次未触发模型调用，外部 API 服务可用性不属于此次安装验证。

## 2026-09-23 推荐模型更新

- API 设置改为只推荐 `qwen3.8-27b`，文档按钮指向官方 `Qwen3.8-27B-FP8` 模型页，说明关联已验证的 xspark 部署。点击“填入”仍只修改模型草稿。
- Promise format、lint、check 通过；40 项 JVM 测试及 3 项 API 设置模拟器回归测试通过。Android lint 为 0 错误、8 条原有警告，主 APK 和测试 APK 均通过 API-only 检查。
- 已更新唯一交付件 `android/APM-0.0.2-debug.apk`，内部版本仍为 `0.0.5`（7）。大小 `54764326` 字节，SHA-256 为 `fbe67c2a8f6ebe1b00f6baf010465d9ab42535086c15268c939680309dbdc3a1`。
- 已验证原件保存在 `/private/tmp/apm-qwen-recommend-20260923-CE3B9E15-46FE-48BC-A020-2A69404554FB/outputs/apk/debug/app-debug.apk`。
- 构建及测试日志：`/private/tmp/apm-qwen-recommend-build.log`、`/private/tmp/apm-qwen-recommend-instrument.log`。
- 两次设备枚举均未检测到真实手机；本次未更新真机，也未修改真机配置或数据库。

## 2026-09-23 推荐切换为 Qwen3.6-35B-A3B NVFP4

- 设置页推荐显示 `Qwen3.6-35B-A3B NVFP4`，填入本次实测服务别名 `qwen3.6-35b-a3b`；文档链接指向 NVIDIA 官方量化模型页。点击仍只修改模型草稿，已保存地址、密钥、许可与模型均保持不变。
- 推荐依据为 xspark 上的单张合成图实测：完整标注中位数 10.52 秒。文案不再沿用旧模型“已通过 new-api 验证”的说明；本轮未部署服务、未更改 new-api，网关仍仅提供原来的 Qwen 27B 模型和 IndexTTS。
- Promise format、lint、check 通过，0 问题；40 项 JVM 测试、3 项 API 设置模拟器回归测试通过；lint 0 错误、8 条原有警告；主 APK 与测试 APK 均通过 API-only 检查。
- 唯一交付件 `android/APM-0.0.2-debug.apk` 已更新，内部版本仍为 `0.0.5`（7），大小 `54764378` 字节，SHA-256 `beedd04ec820cfbe0b016efcc6a29ba6a91288ad24524215d2d78589edd0670a`。
- 已验证的隔离构建原件：`/private/tmp/apm-qwen36-recommend-506f6617fe024935bab680a885bce90e/outputs/apk/debug/app-debug.apk`。
- 构建与回归日志：`/private/tmp/apm-qwen36-evidence-pwdzxvtz/build.log`、`/private/tmp/apm-qwen36-evidence-pwdzxvtz/instrument.log`。模拟器使用只读 AVD，测试仅针对 `emulator-5560`，未在真实手机运行仪器测试。
- 构建期间小米 `23127PN0CC`（`设备序列号已省略`）重新连接。确认已安装同包且私有数据库存在后，使用 `adb install -r` 更新成功。安装后的 APK 哈希与交付件一致，首次安装时间未变；schema 8、SQLite `quick_check=ok`，15 张表的逐行哈希及加密 API 配置哈希均与更新前一致。
- 真机前后证据与校验结果保存在权限受限目录 `/private/tmp/apm-qwen36-evidence-pwdzxvtz`；未触发相册标注或修改已保存模型。

## 2026-09-23 Qwen3.6 NVFP4 接入 new-api

- 后续部署已完成：`qwen36-35b-a3b.service` 为 `active/running/enabled`，new-api 渠道 ID 4 已启用，模型 ID 为 `qwen3.6-35b-a3b`。上节“未部署”记录描述推荐项更新时的状态。
- 通过网关完成文字、APM 连接测试等价请求、合成图片标注与搜索规划；耗时分别为 0.237、3.767、9.243、0.968 秒。结构校验通过，四处 OCR 和苹果、香蕉数量正确，搜索结果包含“天黑”“海边”且为 `match=all`。
- 后端与网关鉴权检查通过，临时令牌已删除；原有令牌和渠道保持不变，27B 未重启、IndexTTS 正常，H3 仍停止。此轮只部署服务并更新文档，没有安装 APK 或修改手机配置。
- 完整参数、质量限制、资源采样和运维方式见 [部署记录](xspark-qwen3.6-35b-a3b-nvfp4.md)。服务端证据位于 `/home/jinof/services/qwen3.6-35b-a3b-nvfp4`。
