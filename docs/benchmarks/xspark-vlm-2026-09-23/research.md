# 候选模型与选择依据

调研日期：2026-09-23。筛选条件是能在单台 xspark 上运行、保留视觉输入、可用 OpenAI 兼容接口输出 APM JSON，以及有明确的官方权重来源。最终判断以本机测量和模型原始回复为依据。

| 候选 | 本次测试目的 | 官方依据 |
|---|---|---|
| Qwen3.8-27B FP8 | 复现当前速度，再用同权重比较 CUDA Graph | [Qwen 官方权重](https://huggingface.co/Qwen/Qwen3.8-27B-FP8) |
| Qwen3.5-4B BF16 | 轻量中文多模态方案 | [Qwen 官方模型卡](https://huggingface.co/Qwen/Qwen3.5-4B)：提供图片输入、兼容 API 和关闭思考的用法 |
| Qwen3.5-9B BF16 | 判断增大模型是否带来值得付出延迟的效果收益 | [Qwen 官方模型卡](https://huggingface.co/Qwen/Qwen3.5-9B) |
| Qwen3.6-35B-A3B NVFP4 | 测试低激活参数 MoE 与量化的组合 | [NVIDIA 官方权重](https://huggingface.co/nvidia/Qwen3.6-35B-A3B-NVFP4)、[DGX Spark 推荐列表](https://build.nvidia.com/spark/vllm/agent-ready-models)、[vLLM 官方配方](https://recipes.vllm.ai/Qwen/Qwen3.6-35B-A3B?hardware=dgx_spark_gb10) |
| Gemma 4 E2B BF16 | 加入不同架构和训练系列的轻量对照 | [Google 官方模型卡](https://huggingface.co/google/gemma-4-E2B-it)、[Google 发布说明](https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/) |

Qwen3.6-35B-A3B 的 35B 是总参数规模，单 token 激活约 3B；NVIDIA 检查点使用混合量化，不能把整个模型视为统一四位精度。Gemma 的 E2B 是有效规模名称，本次 BF16 权重文件约 10.25 GB，不能按“总共只有 2B 参数”估算占用。

Qwen3.5 默认会思考，本次所有模型均通过聊天模板参数关闭思考并检查实际响应。图片输入、Schema 接受成功与视觉内容正确是三个不同的验证层次，均需实测。

本机已经有 Qwen3.5 4B、9B 和 NVIDIA 35B MoE 权重，优先复用；Gemma 单独下载到新的模型目录，固定官方版本并核对校验和。已有的 Flash-Next NVFP4 权重文件合计约 135.2 GB，超出本次与生产服务共同驻留的资源预算，未进行推理测试。Gemma 3、Qwen3-VL 等旧系列没有纳入本轮实测；不能据本报告推断其速度或效果。

## 运行参数选择

当前 27B 生产配置使用 eager。CUDA Graph 对照通过 `FULL_DECODE_ONLY` 捕获单并发解码，保持同一权重；这一机制与 torch.compile 的算子编译不同，见 [vLLM CUDA Graph 设计说明](https://github.com/vllm-project/vllm/blob/main/docs/design/cuda_graphs.md)。本次不启用 torch.compile、MTP 或其他投机解码，结果是所记录配置下的实测，并非各模型的速度上限。

35B MoE 使用 vLLM 官方 Spark 配方中的 Marlin MoE 与 FlashInfer attention，但上下文、并发和 KV 缓存统一为本轮参数。运行日志显示 Marlin 路径使用 weight-only FP4；报告不把它描述为原生 FP4 计算吞吐。原始 [配方 YAML](https://github.com/vllm-project/recipes/blob/main/models/Qwen/Qwen3.6-35B-A3B.yaml) 还包含更大并发、FP8 KV、MTP 等方案，本轮没有混用这些设置。
