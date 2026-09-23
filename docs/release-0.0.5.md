# APM 0.0.5

## 更新

- 模型能力统一通过外部 Chat Completions API 接入，移除 APK 中的推理运行库、模型权重和本地模型部署脚本。
- 底部保留相册、热力图、Agent 和设置四页；移除人物／宠物注册识别及重复／相似检测入口。
- API 设置支持地址、模型、密钥、传输许可和“测试连接”。测试使用生成的小图片与生产标注结构，支持未保存配置，不读取相册。
- 推荐模型改为 Qwen3.6-35B-A3B NVFP4，填入 `qwen3.6-35b-a3b`。xspark 的 new-api 已完成文字、图片、OCR 和搜索规划验收。
- 标注保留中文描述、标签、可见文字与结构化检索字段；Agent 按原子搜索条件组合结果，不上传身份目录。
- SQLite schema 8 保留历史照片、标注、身份、模板和特征记录，旧标注保持可读；密钥继续由 Android Keystore 加密保存。
- Python CLI 同步采用外部 API；包版本与 Android 统一为 0.0.5。当前功能、接口、模型实测和部署记录归入 `docs`。

## 验证

2026-09-23 发布检查：Promise format、lint、check 通过；23 项 Python 测试、40 项 JVM 测试通过；隔离构建完成，Android lint 无错误、8 条警告。主 APK 和测试 APK 均通过 API-only 检查。

此前功能验收的 22 项模拟器测试，以及推荐模型更新后的 3 项设置回归测试均通过，本次未重复运行。重新构建的主 APK 与以下交付件逐个 ZIP 内容项比较一致（签名元数据除外），交付件 APK v2 签名验证通过。

交付件此前已通过真机 `adb install -r` 保留数据更新，15 张表的逐行哈希、加密 API 设置及首次安装时间保持不变，数据库 `quick_check=ok`。发布时手机未连接，未再次安装。完整证据见 [0.0.5 验收记录](roadmap-0.0.5-validation.md)。

## 下载与校验

- [GitHub Release](https://github.com/Jinof/apm/releases/tag/v0.0.5)
- 交付文件：`APM-0.0.2-debug.apk`，沿用仓库唯一 APK 命名；实际安装版本为 **0.0.5（versionCode 7）**。
- 文件大小：54,764,378 字节，约 52.23 MiB。
- SHA-256：`beedd04ec820cfbe0b016efcc6a29ba6a91288ad24524215d2d78589edd0670a`。
- Release 同时提供 `SHA256SUMS`。APK 为 debug 签名构建，与现有安装延续同包更新方式。

模型实测只覆盖合成图片样本，不能保证真实图库中的描述准确。旧版本升级不会自动切换已保存的 API 模型；使用新推荐需在设置中填入、测试并保存。
