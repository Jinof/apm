# API-only 精简验收（2026-09-22）

> 本文记录基础精简及首次真机更新；当时的后续设置验收见 [API 设置验收](api-connections-validation.md)，当前交付见 [0.0.5 验收](roadmap-0.0.5-validation.md)。

APM 已收敛为 Android 和 Python API 客户端，保留相册、标注、本地搜索、相似照片及人物/宠物匹配。仓内模型权重、推理运行库、模型导出与校准脚本、模型服务及部署配置已移除。未修改已部署的外部服务；真实手机已完成保留数据更新，核对结果见下文。

## 已验证行为

- 标注与 Agent 共用 Chat Completions API；默认地址和模型为空，显式配置和许可后才可运行。
- 普通标注只需要 VLM API；视觉 API 禁用时跳过额外检测，已有本地身份标记仍可使用。
- 地址、模型、密钥和许可使用 Keystore 加密；旧显式 Ollama 配置迁移到同一主机和代理路径下的 `/v1`，保留许可。正在运行的任务继续使用启动时配置，保存修改作用于后续任务。
- 新 API 标注记录 `openai-compatible` 来源，旧 Ollama 记录保持原样；增量判断包含提供方、模型及提示词版本。
- 请求拒绝重定向；响应拒绝未完成、refusal、类型不合法、小数数量、越界数值、错误模型/版本或异常向量。错误不会覆盖旧标注、视觉特征或模板。
- 真实 MainActivity 操作验证：只配置 VLM 完成所选照片标注，匿名 P1 被发送而本地身份姓名未发送；随后模拟 401 保留全部旧标注字段。
- 相册热力图、原始分辨率查看、缩放、相邻照片导航通过模拟器验证；测试生成唯一图片并清理自己的数据。

## 检查结果

| 检查 | 结果 |
|---|---|
| Promise v34 format / lint / check | 通过 |
| Python 3.13 | 24 项通过 |
| Android JVM | 67 项通过，0 跳过 |
| Android 仪器测试 | 32 项通过（明确启用完整相册、查看器及标注流程） |
| Android lint | 0 错误、8 条提醒 |
| 主 APK 与测试 APK | 均无模型权重或 ONNX/OpenCV/LiteRT 推理运行库 |
| 打包检查器 | 接受纯客户端样本，拒绝模型文件和推理类样本 |
| git diff --check | 通过 |
| 真实手机 | 小米 23127PN0CC（设备序列号已省略）已通过 `adb install -r` 更新，启动及数据库迁移验证通过 |
| 真实外部模型服务 | 本轮未调用；使用本机 mock HTTP 验证客户端合同 |

## 交付来源

- 唯一交付文件：`android/APM-0.0.2-debug.apk`
- 已测试主 APK：`/private/tmp/apm-api-20260922-c/outputs/apk/debug/app-debug.apk`
- 已测试测试 APK：`/private/tmp/apm-api-20260922-c/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- 主 APK 大小：55,184,582 字节
- SHA-256：`a31146e1c64d743c5708b84c6fee87a2ee25b4efb1783aebbe38e9841cc3afac`
- 主 APK 与完整门禁构建 b 的主 APK 字节一致；构建 c 仅更新了查看器测试样本隔离。
- 完整门禁日志：`/private/tmp/apm-api-check-b.log`
- 最终构建与 lint：`/private/tmp/apm-api-build-c.log`
- 最终仪器测试：`/private/tmp/apm-api-instrumentation-c.log`
- 模拟器：`emulator-5580`，API 35；安装后 `com.jinof.apm` 及 `apm-android.sqlite3` 均存在。

自动化测试证明客户端请求、响应与持久化边界，不代表任意外部服务的部署可用性或模型质量。

## 真实手机保留数据更新

2026-09-22 对已安装的 `com.jinof.apm` 执行同包 `adb install -r`，返回 `Success`。更新前后版本为 `0.0.4 (6)`；首次安装时间保持 `2026-08-13 20:50:20`，本次更新时间为 `2026-09-22 21:33:07`。设备实际安装的 `base.apk` SHA-256 与上述交付件一致。

- 启动前：数据库、WAL、SHM 文件的字节数与 SHA-256 均与安装前完全一致；全部表记录及旧模型文件哈希一致。
- 启动后：数据库从 v7 升级到 v8，`PRAGMA quick_check` 返回 `ok`。排除按设计移除的 `patch_grid`、`grid_size` 列后，全部表的记录数及保留字段逐行哈希均一致。
- 保留 7,767 条照片资产、7,775 条位置、3 条标注、1 个人物身份、2 个模板、35 条人脸观察、1,216 条整图特征、2,927 条一般主体记录；其他表记录数也保持一致。旧手机模型文件未删除或改变。
- `MainActivity` 冷启动成功，耗时 2,104 ms，进入前台；复核进程仍运行，未发现该进程的 `AndroidRuntime` 崩溃日志。
- 没有向真机安装测试 APK、运行仪器测试、卸载应用、清理数据或触发模型调用。

本机私有验收快照与比较结果位于 `/private/tmp/apm-device-update-20260922/`，包括安装前、安装后未启动、启动迁移后三个阶段及 `install-comparison.json`、`launch-comparison.json`；快照不纳入仓库。
