# 远程视觉推理验收（2026-09-10）

> 历史验收记录。当前版本已移除仓内模型及部署代码，接口以 [外部 API 契约](external-apis.md) 为准；本文的 APK、服务状态和测试数据仅代表记录日期。

代码及 xspark 服务部署已完成；真机未连接，真实手机的保留数据升级仍待完成。没有向真实手机安装测试 APK、卸载应用或清理数据。

## 已交付行为

- 生产 Android APK 不包含 ONNX/TFLite 模型或任何推理原生 `.so`，全部视觉推理由独立私网服务执行。手机保留照片读取、身份匹配、相似度计算和数据库。
- 单独配置视觉服务地址、访问密钥和传输许可；配置以 Android Keystore 加密，保存在 no-backup 目录。撤销授权后停止新推理，仍能查看已生成的相似结果。
- 远程处理版本增加 `-remote-v1`。旧索引保留到对应照片成功重新生成；不把不同版本直接视为同一索引，不删除旧身份模板或手机模型文件。
- 每张照片的整图、局部和一般主体响应须全部校验成功才写入。断网、超时、401、429、503、错误版本、异常向量和缺字段均保留原有特征。

## 验证结果

| 验证 | 结果 |
|---|---|
| Promise format / lint / check | 通过，v32 |
| Python CLI 回归 | 11 项通过 |
| Android JVM | 50 项通过 |
| Android lint | 0 错误，7 项依赖更新/既有 Compose 或备份配置提醒 |
| 视觉服务协议及并发 | 6 项通过 |
| 模拟器：远程模型、失败保留、设置交互、数据库回归 | 26 项通过 |
| 原生库与模型 APK 清单 | 生产包中均无 |
| xspark 回环、局域网、Tailscale 鉴权访问 | 均通过 |
| 常驻服务重启恢复 | 通过，systemd user 服务，linger 已开启 |
| 真实手机更新与数据核对 | 待手机连接，未执行 |

固定公开样本的新旧向量余弦相似度：SFace `1.0`、猫 embedding `0.99949056`、DINOv3 整图 `0.99980307`。16 个局部网格及 5 个一般主体通过向量对照；旧猫狗模板通过远程特征匹配验证。人脸旧模板对同人查询为 `0.5870204`，异人为 `0.24296254`，分别满足原有 `0.55` 门槛的匹配与拒绝条件。

这些是固定样本兼容性验证，不是完整图库的准确率评估。Skia 与 OpenCV 缩放、以及运行时的差异会影响部分检测边界；水果样本出现橙子/苹果类别差异，狗检测结果也可能与旧运行时不同。因此检测和索引使用新处理版本，匹配阈值不变，低置信度仍保留未知。

xspark 使用 CPU 推理：ONNX Runtime 4 线程、OpenCV/LiteRT 2 线程、全服务单并发；systemd 限制 4 CPU / 6 GiB。实测服务常驻内存约 1.3 GiB。没有占用新的 GPU 模型槽，也没有修改既有模型服务。部署、密钥位置与接口见 [现行接口说明](external-apis.md)。

## 产物与复核

- 规范交付 APK：`android/APM-0.0.2-debug.apk`
- 已测试主 APK 来源：`/private/tmp/apm-remote-visual-20260910-g/outputs/apk/debug/app-debug.apk`
- 最终测试 APK 来源：`/private/tmp/apm-remote-visual-20260910-j/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- 主 APK SHA-256：`45edf1b65256237dbd2a27fd441b6e35d5eae1798e2dade43b43ac6a1d6534d8`
- 最终模拟器日志：`/private/tmp/apm-remote-acceptance-j.log`（`OK (26 tests)`）
- 主 APK 构建/JVM/lint 日志：`/private/tmp/apm-remote-build-g.log`
- Python 日志：`/private/tmp/apm-remote-python-tests.log`
- Promise 报告：`/private/tmp/apm-remote-promise-check.json`

测试 APK 独立包含旧引擎作为数值基准，不能用于真实手机测试。真实手机连接后，须先核实序列号、`com.jinof.apm` 已安装及版本/数据库状态，再对规范主 APK 执行 `adb install -r`，最后核对数据及远程功能；不得卸载或清理数据。
