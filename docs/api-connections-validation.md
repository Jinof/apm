# API 设置测试连接与模型推荐验收（2026-09-22）

> 历史验收记录（0.0.4）。0.0.5 已移除视觉 API 和身份识别，当前交付见 [0.0.5 验收](roadmap-0.0.5-validation.md)。

两类 API 均已提供独立的“测试连接”，使用当前未保存的配置并遵守显式许可。测试结果会在配置修改后清空；测试期间锁定对应字段和按钮，避免重复请求或过期结果。

- 标注 / Agent API：发送一张生成的 64×64 JPEG，使用实际标注 schema 校验完整结果；不读取照片、姓名或历史标注，不保存设置或写入索引。UI 说明可能产生 API 费用。
- 视觉 API：鉴权调用 `GET /v1/models` 并验证所有兼容字段，明确告知成功未执行图片推理。
- 模型推荐：[gpt-5.6-terra](https://developers.openai.com/api/docs/models/gpt-5.6-terra) 用于均衡选择，[gpt-5.6-luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna) 用于更重视成本的批量任务。官方能力于 2026-09-22 核实；推荐基于文档而非私人图库实测。填入只改变草稿模型名，不改变地址、许可或保存状态。
- 视觉模型说明：当前兼容 DINOv3 ConvNeXt Large、YuNet/SFace、SSD MobileNet V1/MobileNetV3，保持已有向量及身份模板兼容。

## 验证

| 检查 | 结果 |
|---|---|
| Promise v35 format / lint / check | 通过 |
| Python | 24 项通过 |
| Android JVM | 67 项通过 |
| 模拟器仪器测试 | 35 项通过 |
| Android lint | 0 错误，8 条提醒 |
| 主 APK / 测试 APK | 无模型权重或推理运行库 |
| 真机保留数据更新 | `设备序列号已省略`，安装和启动成功 |

新增设置页测试覆盖：未保存配置、Bearer 和所选模型、生成图片尺寸及真实 schema、运行时防重复、401 与无效 schema 的失败提示和重试、撤销许可、修改输入清除结果、推荐模型只填入字段，以及视觉接口使用未保存地址并保留原配置。

接口功能在本机 mock HTTP 服务上验证；本轮没有使用用户密钥调用真实外部模型服务。

## 产物与设备

- 规范 APK：`android/APM-0.0.2-debug.apk`
- 隔离来源：`/private/tmp/apm-connections-20260922-a/outputs/apk/debug/app-debug.apk`
- SHA-256：`4aee9c7c58bc21391eda243d475b4bbfe9573988fb73ea9cbde7de85d10aadd9`
- 大小：55,207,958 字节
- 完整门禁日志：`/private/tmp/apm-connections-check-a.log`
- 模拟器日志：`/private/tmp/apm-connections-instrumentation-a.log`，`OK (35 tests)`

真机为小米 23127PN0CC，已确认原先安装 `com.jinof.apm`，随后仅执行 `adb install -r`。实际安装 APK 哈希与交付件一致，版本仍为 `0.0.4 (6)`，更新时间 `2026-09-22 21:50:10`，首次安装时间保持 `2026-08-13 20:50:20`。

安装后未启动阶段的数据库、WAL 和 SHM 文件与更新前逐字节一致。启动后数据库仍为 v8、`quick_check=ok`；所有表记录数及保留字段逐行哈希保持一致，旧模型文件哈希也一致。冷启动耗时 2,168 ms，进程持续运行，未出现该进程的 AndroidRuntime 崩溃。

验收快照与比较记录保存在本机私有目录 `/private/tmp/apm-connections-device-20260922/`，不纳入仓库。未在真机运行仪器测试，未卸载或清理应用数据。
