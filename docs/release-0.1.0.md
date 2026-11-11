# APM 0.1.0

2026-11-11，Android 版本 **0.1.0（versionCode 12）**。

## 更新

- 每个 API 独立设置标注并发度，范围 **1–8，默认 8**。旧配置缺少此字段时也读取为 8；配置继续加密保存。
- 全量与所选标注按设置的上限并行处理，完成一张后补充下一张。任务使用启动时的 API 配置，途中修改或切换只影响后续任务。
- 多张照片可同时显示处理中圆圈，任务卡片显示并发占用、当前文件、成功／失败／跳过数量和 ETA。
- 乱序响应按照片 ID 保存。单张失败保留原有标注，其余照片继续；退出任务时取消在途请求并阻止迟到写库。
- 包含此前未单独发布的 0.0.9 启动改动：“让照片，说出自己的故事”显示在首次加载阶段，加载完成后自动进入首页。

xspark 的 Qwen3.6 MoE 后端已同步启用 8 路。通过 new-api 实测，8 次合成图片标注全部成功，整批 **22.81 秒**、总输出 **243.05 token/s**。这是该机器上短图片请求的结果；其他 API 的实际吞吐取决于服务端配置。详见 [并发实测报告](benchmarks/xspark-qwen-concurrency-2026-11-11/README.md)。

## 验证与升级

- Promise、23 项 Python、48 项 JVM 和 39 项模拟器测试通过；Android lint 0 错误、7 条警告、2 条信息。
- 发布时复核功能代码未变，APK 与已测试原件逐字节一致，签名、版本和 API-only 检查通过，沿用同一份验收 APK。
- 包名仍为 `com.jinof.apm`，继续使用原 debug 签名；SQLite 保持 schema 8，Python CLI 保持 0.0.5。
- 已在小米真机通过 `adb install -r` 从 0.0.9 更新，安装前后及启动后的数据库、加密 API 设置、权限和首次安装时间核对一致。

完整记录见 [0.1.0 验收记录](roadmap-0.1.0-validation.md)。

## 下载与校验

- [GitHub Release](https://github.com/Jinof/apm/releases/tag/v0.1.0)
- [下载安装包](https://github.com/Jinof/apm/releases/download/v0.1.0/APM-0.0.2-debug.apk)
- APK 文件名为 `APM-0.0.2-debug.apk`，沿用唯一交付物约定；实际版本为 **0.1.0（12）**。
- 大小：54,894,786 字节，约 52.35 MiB。
- SHA-256：`2cd0060b95612eb56726841698252c8d82f04917251d4e5a65bce082d0d54ff1`。
- Release 同时提供 `SHA256SUMS`。
