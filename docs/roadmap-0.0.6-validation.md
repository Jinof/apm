# Roadmap 0.0.6 验收记录

2026-09-24 完成。Android 包名 `com.jinof.apm`，版本 **0.0.6（8）**；Python CLI 保持 0.0.5。此次为本地交付和真机更新，没有提交、打标签或创建 GitHub Release。

## 行为

“标注所选”只保留当前 MainActivity 会话的选择。新 ViewModel 会话在首次读取数量、展示页面和处理选图回调之前清空 `annotation_selection`；配置重建复用 ViewModel，不重复清空。首页系统返回结束 Activity；Home 键切后台、页面切换和查看器返回不清空，`onResume` 只刷新现有状态。

选图完成替换当前选择，取消保留原范围。关闭的会话拒绝迟到的选图写入，避免旧任务把选择带回新会话。照片授权、目录、历史标注、旧身份与特征、加密 API 配置均保留，SQLite 仍为 schema 8。

## 检查结果

| 项目 | 结果 |
|---|---|
| Promise v37 | format、lint、check 通过，0 问题 |
| Python | 23 项通过 |
| JVM | 40 项通过 |
| 原有模拟器回归 | 22 项通过，包含 API 设置、标注失败保留、数据库、查看器与 Dock／热力图 |
| 新增生命周期回归 | 4 项通过，覆盖启动清空、选图回调完成／取消、Activity 配置重建、后台返回、Dock 切换、查看器返回、首页退出重开、移除任务重开和迟到写入拒绝 |
| 真正进程重启 | 两阶段夹具检查各 1 项通过；模拟器 force-stop 后冷启动，选择 1 → 0，其他表逐行哈希一致 |
| Android lint | 0 错误、8 条原有警告、1 条信息 |
| APK | 主 APK、测试 APK 均通过 API-only 检查；主 APK 签名验证通过 |

模拟器为专用只读 AVD `emulator-5562`，测试未针对真实手机执行。选图结果通过生产回调入口注入生成图片，未自动点击系统选图器；配置重建使用 ActivityScenario.recreate。进程重启另用真实 `am force-stop` 和启动指令验证，未用 Activity 重建代替。

首轮新增测试的查看器启动方式和设置文件读取范围存在夹具问题，修正后四项全部通过；既有 22 项在首轮均通过。重启夹具修正照片 ID 格式后，两阶段均通过。保留原日志，未将失败轮次记为通过。

## 首轮选择会话修复交付 APK

- 唯一交付件：`android/APM-0.0.2-debug.apk`，内部版本为 0.0.6（8）。
- 大小：54,767,406 字节。
- SHA-256：`0b57cda9238095e91cae89a03756050a90ce15f071739aff8da7a3327b437b12`。
- 已测试主 APK 原件：`/private/tmp/apm-006-build-3B0901BB-DFD7-4303-8CA4-1362D1951B58/outputs/apk/debug/app-debug.apk`。
- 重启夹具修正后的测试 APK 原件：`/private/tmp/apm-006-probe-build-A5E5D4FF-77DB-4C28-989A-CF8F4F1EB7F0/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。此次仅重建测试 APK，主应用代码未变。

所有构建均使用新隔离目录，先前 APK 原件保留。

## 真机保留数据更新

确认已连接小米 23127PN0CC，安装包为 `com.jinof.apm` 0.0.5（7）且私有数据库存在后，执行同包 `adb install -r` 更新，返回 `Success`。未卸载、清除应用数据或在真机执行仪器测试。

- 安装完成但尚未启动时：15 张表逐行哈希全部与安装前相同。
- 冷启动后：选择表从 1 条变为 0；其余 14 张表逐行哈希全部相同。
- 加密 API 设置哈希、首次安装时间均保持不变。
- 安装后及启动后的 schema 均为 8，SQLite `quick_check=ok`。
- 真机实际 `base.apk` 的 SHA-256 与交付件完全一致，版本为 0.0.6（8）。

权限受限证据目录：`/private/tmp/apm-006-evidence-3V6GKIdK`。包含 `build-verified.log`、`instrument.log`、`lifecycle-verified.log`、重启两阶段日志、`process-restart-verification.json`、安装前／安装后／启动后快照和 `device-verification.json`。数据库快照与设备序列号不进入仓库。

## 2026-09-24 补充：已标注照片绿色勾

已完成 README 新增条目。相册缩略图、详情图片和本地搜索结果缩略图在右下角显示 32 dp 绿色勾，距图片边缘 6 dp，白色圆底增强对比，无障碍描述为“已标注”。未标注时隐藏；存在任意已保存标注即可显示，不依赖当前模型或提示词版本，也不表示照片被选中。标记不修改原图、不拦截照片点击，标注任务完成后随相册状态刷新。

- Promise 更新为 v38，format、lint、check 通过；23 项 Python、40 项 JVM 测试通过。
- 本轮 12 项模拟器回归通过，覆盖绿色勾、标注成功／失败保留、会话生命周期、相册导航、热力图和查看器。首轮三项仅因截图目录不可写失败，切换应用缓存目录后复测通过，未改动显示逻辑。
- 新增测试验证：未标注隐藏、已有标注显示、状态切换立即更新、右下角位置、实际绿色像素、无障碍名称、点击标记仍能打开照片，以及深色主题。已查看两种相册模式的测试截图。
- Android lint 0 错误、8 条原有警告、1 条信息；主 APK 和测试 APK 通过 API-only 检查。
- 真实小米手机已通过 `adb install -r` 更新，版本仍为 0.0.6（8）。安装前后 15 张表一致；启动时临时选择从 2 条变为 0，其余 14 张表、加密 API 配置及首次安装时间一致，schema 8、`quick_check=ok`。

绿色勾补充轮次的交付件：`android/APM-0.0.2-debug.apk`，54,768,174 字节，SHA-256 `d627503bdaed2d9bf41ad341722b270de0a31acead4839f9b3c42079ba0c7168`。真机实际安装包哈希一致。

隔离原件保留在 `/private/tmp/apm-badge-build-BC760403-4045-473E-8B88-A9A59930A0F8/outputs/apk/debug/app-debug.apk`；构建、模拟器日志、测试截图及真机前后证据位于 `/private/tmp/apm-006-badge-45HViyI5`。没有修改数据库结构、Python 版本或外部模型服务，没有创建 GitHub Release。

## 2026-09-25 补充：旋转圆圈、任务卡片与 ETA

完成 README 两项新增要求。相册缩略图、详情和本地搜索中的当前处理照片右下角显示旋转圆圈，暂时代替绿色勾；写入成功后立即切回绿色勾，失败则恢复旧标注标记或不显示标记。

“全量标注”改为任务卡片，保留开始按钮，展示本次任务范围、阶段、当前文件、已处理／待处理数量、成功／失败／跳过数量、累计耗时和最近五条日志。所选标注也使用同一进度展示。日志包含准备缩略图、请求模型、校验结构、写入、成功／失败等阶段，内存最多保留 40 条，不包含密钥、原始请求／响应或异常原文。

ETA 使用单调时钟，以已完成尝试的平均耗时估算剩余待处理队列，并扣除当前图片已用时间。扫描时和首张完成前显示等待说明，超出估计时显示“重新估算中”，任务结束后清除当前图片和 ETA 并停止计时。跳过照片不计入待处理工作量。空队列正常结束，失败请求计入失败数并保留历史标注。任务进度不持久化；退出或更新后重新全量标注会跳过已有当前模型结果。

### 验收

- Promise v39 format、lint、check 通过，0 问题；23 项 Python、43 项 JVM 测试通过。
- 本轮 13 项不同模拟器回归全部通过：受控慢 API 的当前文件／首张 ETA／成功失败计数、任务卡片、旋转标记优先级与结束恢复、会话生命周期、Dock／热力图和查看器。
- 新增 JVM 测试覆盖 ETA 样本、当前耗时扣除、失败计数、零工作量、结束后停止计时、日志上限和错误信息脱敏。
- 最初的子串断言、重复使用已移除节点查询和页面切换等待存在测试问题，修正测试后复核通过；原失败日志保留。逐项最终结果见本地证据中的 `test-verification.json`。
- 主 APK 与最终测试 APK 均通过 API-only 检查，主 APK 签名验证通过。Android lint 0 错误、8 条原有警告、2 条信息。
- 已查看模拟任务卡片截图，当前图片、日志、计数和 ETA 排版可读。本次使用本地 mock API，未新增真实照片的模型请求。

### 真机与当前交付件

真机重新连接后确认仍安装 `com.jinof.apm` 0.0.6（8），按 `adb install -r` 保留数据更新，返回 Success，冷启动成功。

安装前采样时原应用仍在标注，标注数在两次采样间从 489 增至 499。已逐行确认全部 489 条旧记录完整保留，仅新增 10 条；其余 14 张表逐行哈希一致。安装后到启动后的 15 张表全部一致，加密 API 设置和首次安装时间不变，schema 8、`quick_check=ok`。本轮没有卸载、清空数据或在真机执行仪器测试。

**当前交付件以本节为准**：`android/APM-0.0.2-debug.apk`，内部版本 0.0.6（8），54,792,686 字节，SHA-256 `ec86f04a4761cc9fa0e1c48279f97824d312bd69450f7d5b00a779600f0e95c8`。实际安装的 APK 哈希一致。

已验证主 APK 原件保留在 `/private/tmp/apm-progress-build-CFD8B344-9588-4D2B-8E07-FF516A8FEE97/outputs/apk/debug/app-debug.apk`；最终测试 APK 位于 `/private/tmp/apm-progress-tests-F78BABFD-807F-46A2-9884-09D4F9A74DE5/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。证据位于权限受限目录 `/private/tmp/apm-006-progress-cM5cPIGm`，包含构建、三轮回归日志、截图和真机数据比较。未创建 GitHub Release。
