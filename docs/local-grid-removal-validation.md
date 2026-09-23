# 移除局部网格验收（2026-09-11）

> 历史验收记录。当前版本已移除仓内模型及部署代码，接口以 [外部 API 契约](external-apis.md) 为准；本文的 APK、服务状态和测试数据仅代表记录日期。

已移除局部网格生成、API 响应字段、Android 数据结构与编解码、数据库列、构图评分和界面展示。全部模型继续运行在外部服务器；本次没有更换 DINOv3、人脸或宠物模型。

## 行为与数据

- 保留 DINOv3 整图 1536 维向量、一般主体向量和人脸/宠物身份功能。
- 相似检查使用整图、主体和拍摄时间；不再产生“构图相似”分类。场景分数为整图余弦；主体分数为 0.30 整图 + 0.70 主体；满足整图至少 0.84 且时间间隔不超过 10 秒时，连拍分数为 0.90 整图 + 0.10 时间接近度。最终排序取可用分数的最大值。
- 视觉流水线改为 `dinov3-convnext-large-lvd1689m-similarity-v2-remote-v1`。旧流水线索引保留原版本，需重新检查，逐张完整成功后替换；不混用新旧版本。
- SQLite v7 → v8 事务迁移仅移除 `patch_grid`、`grid_size`，保留整图向量及来源、一般主体、标注与身份模板。升级不修改原照片。
- 推理或写入失败时，原有成功记录保留。网格缺失不再阻止新响应提交。检查范围和增量跳过行为保持。
- 固定 ONNX 仍输出 50 个 token，服务只提取整图 token；未重新导出或覆盖仓库权重。

## 验证结果

| 检查 | 结果 |
|---|---|
| Promise v33 format / lint / check | 通过 |
| Android JVM | 52 项通过 |
| Android lint | 0 错误；7 项既有提醒 |
| Python CLI | 11 项通过 |
| xspark ARM64 服务测试 | 8 项通过 |
| 模拟器远程、设置、数据库集成 | 27 项通过 |
| 生产 APK 权重及视觉推理运行库检查 | 无 ONNX / TFLite 权重及相关原生运行库 |
| xspark 服务更新、重启、回环及局域网鉴权请求 | 通过 |
| 真机安装 | 未执行；ADB 仅有 emulator-5554 |

使用仓库公开水果样本，在同一 xspark 运行时对照旧、新实现：整图向量和全部 5 个一般主体向量及检测结果完全一致；新 `/v1/visual` 不返回 `patch_grid`。该结果验证保留特征不变，不代表所有图库在移除构图评分后的准确率评估。

模拟器覆盖实际 DINOv3 输出、相似图片排序、旧人脸与宠物模板匹配、401/429/503/超时/错误版本/异常向量/缺少主体字段保留数据、增量跳过，以及含身份模板的 v7 数据库升级。事务测试故意在新主体插入中制造唯一键冲突，确认旧整图及主体记录完整回滚。

## 交付与来源

- 唯一交付件：`android/APM-0.0.2-debug.apk`
- 已测试主 APK：`/private/tmp/apm-no-grid-20260911-a/outputs/apk/debug/app-debug.apk`
- 模拟器测试 APK：`/private/tmp/apm-no-grid-20260911-a/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- 主 APK 大小：55,206,802 字节
- 主 APK SHA-256：`72c9235a92514c7f7e0de65977fe0575b827756067617ccedf300f22812f9427`
- 构建/JVM/lint：`/private/tmp/apm-no-grid-build-a.log`
- 模拟器：`/private/tmp/apm-no-grid-acceptance-a.log`
- Promise：`/private/tmp/apm-no-grid-promise-check.json`
- Python：`/private/tmp/apm-no-grid-python.log`
- 新旧特征对照：`/private/tmp/apm-no-grid-parity.log`
- 服务实请求：`/private/tmp/apm-no-grid-live.log`

xspark 独立 `apm-visual.service` 已更新；权重、鉴权配置和其他服务保持原状。先前服务代码保留在 xspark `/tmp/apm-no-grid-20260911-a/previous_service.py`。

旧 APK 的严格版本校验会拒绝新视觉流程，需安装本次交付 APK 后再检查。真实手机连接后，先核实 `com.jinof.apm` 已安装、版本及私有数据库状态，再执行 `adb install -r` 并复核数据；不卸载或清理应用。
