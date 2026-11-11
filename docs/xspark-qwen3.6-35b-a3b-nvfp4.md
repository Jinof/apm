# xspark Qwen3.6-35B-A3B NVFP4 部署记录

2026-09-23 部署并验收通过。主机为 xspark（`192.168.2.10`），GPU 为 NVIDIA GB10。新模型与 Qwen3.8-27B、IndexTTS 同时运行，H3 保持停止。

## 客户端配置

| 配置项 | 值 |
|---|---|
| API 基础地址 | `http://192.168.2.10:3000/v1` |
| 模型 ID | `qwen3.6-35b-a3b` |
| 密钥 | 现有 new-api 令牌；已确认 APM 令牌没有模型限制 |
| new-api 渠道 | ID 4，`xspark Qwen3.6 35B-A3B NVFP4`，已启用 |
| 后端 | `http://127.0.0.1:8096`，要求 Bearer 鉴权，仅在 xspark 回环接口监听 |

APM 设置页点击推荐模型的“填入”，确认配置后测试连接、保存即可。此次部署没有改动手机已保存的配置，也没有重新构建或安装 APK。new-api 为此本地模型设置零价格，保留其余模型价格及原有令牌。

## 模型与运行参数

- 权重为 [NVIDIA Qwen3.6-35B-A3B-NVFP4](https://huggingface.co/nvidia/Qwen3.6-35B-A3B-NVFP4)，固定版本 `1355db6a052410cfd62085d94b58866fd0f2c3c5`。复用已下载权重，本次重新计算三个 safetensors 文件的 SHA-256，均与固定版本记录相符，共 23,424,338,320 字节。
- 模型目录：`/home/jinof/models/Qwen3.6-35B-A3B-NVFP4`。
- 部署目录：`/home/jinof/services/qwen3.6-35b-a3b-nvfp4`。
- 复用现有 vLLM 0.28.0、PyTorch 2.13.0、Transformers 5.16.1 和 FlashInfer 0.6.16.post3 环境，离线加载。
- 使用 Marlin NVFP4 计算内核、FlashInfer 注意力和 `FULL_DECODE_ONLY` CUDA Graph，关闭 torch.compile；Graph capture size 为 1。
- 上下文 16,384 token，单并发，批次上限 2,048 token，KV 缓存 2 GiB。默认关闭思考，输出上限 2,048 token。
- 每次请求最多 1 张图片，不启用视频输入；兼容 OpenAI Chat Completions 的图片与严格 JSON Schema 请求。
- 用户服务为 `qwen36-35b-a3b.service`，已通过 systemd 语法检查，状态为 `active/running/enabled`，用户 `Linger=yes`。开机启动已配置，本次未重启整机验证。

## 网关验收

下表均为经过 new-api 的单次实际请求。图片使用此前基准测试的合成咖啡桌图片，没有访问私人相册。

| 请求 | 耗时 | 结果 |
|---|---:|---|
| 文字连通 | 0.237 秒 | 回复“连接正常” |
| APM 设置页连接测试等价请求 | 3.767 秒 | 64×64 纯色 JPEG，使用生产标注提示词与完整 Schema；响应通过校验，低于客户端 60 秒超时 |
| APM 完整图片标注 | 9.243 秒 | JSON Schema 校验通过，正常结束；识别 3 个苹果、2 根香蕉及全部 4 处文字 |
| APM 搜索规划 | 0.968 秒 | “找出天黑时在海边的照片”返回 `queries=["天黑","海边"]`、`match="all"`，Schema 与语义检查通过 |

图片返回的 `visible_text` 为 `海边咖啡 LATTE ¥28 CAKE ¥18 APM 2026`，人物数组为空。输出仍存在不能由图片确认的描述，例如把未亮的手机写成“关机状态”；JSON 合法、计数和 OCR 通过不代表所有描述正确。

这些数值是上线验收结果，不替代此前三轮计时得到的 10.52 秒中位数，也不是并发基准。质量局限见 [多模态模型实测报告](benchmarks/xspark-vlm-2026-09-23/README.md)。16K 是配置上限，本次未做满上下文压力测试。

其他验收结果：

- 后端和网关未授权模型请求均为 401；Mac 通过内网访问网关也返回 401。授权后的两侧模型列表均包含 `qwen3.6-35b-a3b`。
- 验证使用仅限此模型、回环 IP、一小时有效且有额度限制的临时令牌，完成后已删除。
- 原有令牌密钥、模型权限、IP 限制与分组的哈希一致；原有三个渠道的模型、地址和启停状态一致。27B 服务 PID 未变化。
- 新模型、27B、IndexTTS（`/healthz`）及 new-api 健康检查均为 200。新服务重启次数为 0。
- 新模型进程 GPU 内存为 24,582 MiB（约 24.01 GiB）；验收时整机可用内存约 43.54 GiB。这是与 27B、IndexTTS 并驻后的采样值。

上线前 new-api 数据库备份、模型指纹、请求响应、状态校验和日志保存在服务器部署目录，目录权限为 700。密钥和管理会话仅在服务器保存，不写入仓库或本文。

## 运维

在 xspark 上执行：

```bash
systemctl --user status qwen36-35b-a3b.service
systemctl --user restart qwen36-35b-a3b.service
tail -n 80 /home/jinof/services/qwen3.6-35b-a3b-nvfp4/logs/server.log
```

服务启动前检查固定配置、权重校验记录和文件大小，要求 H3 未运行、整机可用内存至少 40 GiB。启动时加载需要时间，应等待 `/health` 返回 200，再判断可用；本次约三分钟完成加载。

若恢复 H3，需先禁用 new-api 中两个 Qwen 渠道（ID 3、4），停止并禁用 `qwen38-27b.service`、`qwen36-35b-a3b.service`，再按 [原部署记录](xspark-qwen3.8-27b.md) 恢复 H3。H3 的大内存占用不适合与这两个模型并驻。


## 2026-11-11：启用 8 路并发

按 Roadmap 0.1.0 调整现有 `qwen36-35b-a3b.service`：`max-num-seqs=8`，CUDA Graph 捕获批次改为 `[1,2,4,8]`。其余模型参数、端口、密钥、new-api 渠道及开机启用状态保持原值。当前服务开机启用状态为 disabled；本次只调整并发，不改变启动策略。

通过现有 new-api 网关同时提交 8 张合成图片标注，8/8 成功且结构校验通过，服务端观测到 8 个请求同时运行：整批 22.81 秒，总输出 243.05 token/s，单次中位数 21.81 秒。该结果是短图片请求测试，不代表 8 个满 16K 上下文的容量保证。

原配置与验收资料在 xspark 的 `/home/jinof/benchmarks/apm-010-rollout-3ix4_w9v/`，原文件为 `qwen36-original.service`。回滚时将它复制回 `/home/jinof/.config/systemd/user/qwen36-35b-a3b.service`，执行 `systemctl --user daemon-reload` 后重启该单元，并重新核对健康与推理。Gemma、IndexTTS、H3 和 Qwen3.8 的服务状态、进程及重启计数前后一致；未改动渠道或既有凭据。

App 配置和交付验证见 [0.1.0 验收记录](roadmap-0.1.0-validation.md)。此前的[并发基准](benchmarks/xspark-qwen-concurrency-2026-11-11/README.md)记录的是本次上线前的单路配置与临时实例对照。
