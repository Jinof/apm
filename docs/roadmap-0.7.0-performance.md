# Roadmap 0.7.0 · 四个页面的切换性能

调查日期为 2026-11-28。基线版本为 APM 0.6.0（18），对应 Git 提交 `5e830ec`。本次记录切页卡顿的原因和实施前基线。

## 卡顿发生在哪里

四页由同一个 `MainActivity` 中的 `HorizontalPager` 承载。点 Dock 会启动横向滚动动画，手势翻页会布局相邻页。正常切页沿用内存中的相册数据。相册目录在启动或从后台返回等时机由工作线程读取，Agent 的远端搜索由搜索按钮触发。[分页器](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/AppDock.kt#L86) · [四页入口](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/MainActivity.kt#L170) · [目录读取](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/MainActivity.kt#L520) · [Agent 搜索](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/AgentSearchActivity.kt#L83)

**首次进入热力图时，慢帧主要耗在 Compose 的测量和布局上。** 空相册的一次独立系统跟踪中，最长的 `Choreographer#doFrame` 为 325 毫秒，其中 292 毫秒在动画回调里的 `AndroidOwner:measureAndLayout`。另一次 ART 采样在主线程记录到约 90 毫秒 CPU 时间位于日历月份组件内。直接从相册点 Agent 时，主线程也在同一月份组件内花了约 54 毫秒 CPU 时间。跨页点击会实际创建经过的日历内容。两种跟踪分别运行。

按日显示时，12 个月各自为全年日期建一次索引，并分配 42 个日历格位。每个有效日期创建一个 Material `Surface`，构造日期格式器和无障碍描述。即使相册为空，也会布局 365 或 366 天。日历数据也在页面组合时同步生成，包括扫描照片、按日期分组和排序，以及分别为周、月统计数量。目录越大，这部分计算涉及的输入越多。[月份布局](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/PhotoHeatmapActivity.kt#L416) · [年度统计入口](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/PhotoHeatmapActivity.kt#L173) · [统计实现](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/PhotoWallModels.kt#L113)

**跨页点按的开销取决于途中经过哪一页。** 在 96 张图的独立采样中，从相册点 Agent 时，日历月份组件占了约 64 毫秒主线程 CPU 时间，年度统计约 8 毫秒。从相册点设置时，调用栈进入了中间 Agent 页的搜索输入框 `OutlinedTextField`，单这一项约 22 毫秒。相同的设置列表在预热后从相邻 Agent 页点入，最慢帧中位数为 18 毫秒。[Agent 的搜索输入框](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/AgentSearchActivity.kt#L165)

**回到看过的照片墙，还会重新请求缩略图。** 在 96 张图的相册首页停留后，先去设置，再回到原来的相册位置。返回时的独立 ART 采样在四个后台工作线程上都捕捉到了 `ContentResolver.loadThumbnail`。同一次采样还在主线程的日历月份组件内记录到约 30 毫秒 CPU 时间。代码中，缩略图缓存由相册或 Agent 页面各自创建，离开页面组合时会丢失。照片墙只检查自身的纵向滚动和滑块拖动状态，所以横向分页期间也会发起可见缩略图请求。[缓存作用域和读取](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/PhotoThumbnailLoader.kt#L25) · [相册加载条件](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/MainActivity.kt#L615) · [Agent 缩略图](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/AgentSearchActivity.kt#L127)

**设置首次打开时会同步读取本地配置。** 为隔离这项开销，每次重启后直接打开 Agent，再点相邻的设置。先测默认配置，随后原样保存同一份默认配置再测，密钥为空，远程传输关闭。保存前的三次最慢帧为 84、84、34 毫秒，保存后为 67、84、21 毫秒。两组范围重叠。单独的方法采样显示，主线程读取加密配置的 `loadProfiles()` 调用历时约 3.9 毫秒，其中约 2.1 毫秒为 CPU 时间。该读取由 Activity 级编辑器在首次初始化时触发。[设置初始化](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/SettingsActivity.kt#L40) · [初始化条件](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/ApiSettingsEditor.kt#L26) · [配置读取](https://github.com/Jinof/apm/blob/5e830ecd4e381093665441a28a156c7f1ec6bccb/android/app/src/main/java/com/jinof/apm/SettingsStore.kt#L35)

## 进程首次从相册点入各页

单位为毫秒。每格给出三次操作中最慢帧的中位数，括号内为各次最慢帧的范围。

| 目标页 | 空相册 | 96 张照片 |
|---|---:|---:|
| 热力图 | 307（300–324） | 334（100–384） |
| Agent | 117（50–350） | 34（34–1008） |
| 设置 | 134（117–150） | 157（150–183） |

Agent 在 96 张图的三次首访中有一次出现约 1 秒的慢帧。这次保留了帧时间，具体调用各占多久仍未知。不同切换方向和首次、再次访问需要分别比较。

## 连续往返

下表是预热后每次切换最慢帧的中位数，单位为毫秒。横滑每次只到相邻页。

| 方向 | 空相册点按 | 空相册横滑 | 96 张点按 | 96 张横滑 |
|---|---:|---:|---:|---:|
| 相册 → 热力图 | 19 | 67 | 21 | 67 |
| 相册 → Agent | 37 | — | 55 | — |
| 相册 → 设置 | 19 | — | 20 | — |
| 热力图 → 相册 | 30 | 34 | 34 | 34 |
| 热力图 → Agent | 26 | 34 | 19 | 35 |
| 热力图 → 设置 | 18 | — | 20 | — |
| Agent → 相册 | 44 | — | 84 | — |
| Agent → 热力图 | 42 | 34 | 54 | 34 |
| Agent → 设置 | 19 | 36 | 18 | 20 |
| 设置 → 相册 | 71 | — | 67 | — |
| 设置 → 热力图 | 52 | — | 34 | — |
| 设置 → Agent | 43 | 59 | 34 | 51 |

## 如何测量

用同一台 Android 15（API 35）ARM64 专用模拟器，4 核、1080 × 2400、420 dpi、60 Hz、宿主 GPU。安装仓库交付的 debug APK，SHA-256 为 `d8b6cc5238c0018fbfb42e2697ffe280394638937fec62af8104c0c2a5ed6232`。先测空相册，再经应用的系统选图器导入 96 张 720 × 540 的 JPEG，每张有不同的内容和拍摄日期，均无标注。应用核对为“已授权并选择 96 张 · 内容校验 96 张”。两组切页基线都使用默认 API 配置。

- **进程首访**。每次分别重启应用，等相册首屏就绪，然后仅点热力图、Agent 或设置中的一个。各目标独立重复三次，采样窗口从点按开始。
- **连续往返**。每轮走遍四页的 12 个有向点按组合，再以 240 毫秒的手势走完 6 个相邻页滑动方向。空相册共三轮，有图相册共四轮。连续往返结果从第二轮起计算。
- **帧耗时**。对比输入前与输入结束 1.2 秒后的 `gfxinfo framestats`，只取主窗口新增、标志为零且已完成的帧。每次切换取 `FrameCompleted − IntendedVsync` 的最大值，重复切换取这些最大值的中位数。每次取完帧再检查实际页名。系统跟踪与 1 毫秒间隔的 ART 方法采样另行采集。[测量脚本](../scripts/measure-dock-frames.py) · [Android 帧统计说明](https://developer.android.com/tools/dumpsys#gfxinfo)

共完成 150 次有帧记录和目标页核对的切换，其中 6 次用于设置对照。证据包收录 4,235 条帧记录、空相册那一帧的主线程系统跟踪，以及 7 份可用 Android Studio 打开的方法采样。帧记录中的 `protocol` 区分首访、连续往返和设置对照，时间戳单位为纳秒。本次连接的真机 ADB 显示未授权，真机帧耗时仍待验证。[原始帧表和调用跟踪](benchmarks/dock-0.7.0/baseline.zip)

复测时在相册首页开始，使用独立的输出文件。

```sh
python3 scripts/measure-dock-frames.py --serial emulator-5580 --rounds 4 --output cycles.csv
python3 scripts/measure-dock-frames.py --serial emulator-5580 --rounds 3 --first-visits --output first-visits.csv
```
