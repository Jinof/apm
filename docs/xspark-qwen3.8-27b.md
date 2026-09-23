# xspark Qwen3.8-27B 部署记录

2026-09-22 开始部署，2026-09-23 验收通过。目标主机 `xspark`，内网地址 `192.168.2.10`，NVIDIA GB10。

## 已上线

- 模型：官方 `Qwen/Qwen3.8-27B-FP8`，保留视觉编码器。
- 固定 Hugging Face 版本：`017b9c7af6b5689d5dd426a76e0bc077eb5ca20a`。
- 已从 Qwen 官方 ModelScope 仓库下载并校验全部 81 个文件，共 30,890,049,597 字节（约 28.77 GiB）；66 个权重文件的 SHA-256 与固定 Hugging Face 版本一致。服务器 `download-result.json` 已报告 `complete=true`。
- 部署目录：xspark 的 `/home/jinof/services/qwen3.8-27b`。
- 模型目录：xspark 的 `/home/jinof/models/Qwen3.8-27B-FP8`。
- 已有运行时：vLLM 0.28.0、Transformers 5.16.1、PyTorch 2.13.0。未修改其环境。
- 用户服务 `qwen38-27b.service` 已安装并通过语法检查，当前为 `active`、`running`、`enabled`，已启用开机启动。
- 视觉处理器已通过离线检查：无需远程代码即可加载 `Qwen3VLProcessor`，图片聊天模板有效。此检查不包含模型推理。
- new-api 的 **ID 3** 渠道 `xspark Qwen3.8 27B` 已启用，模型 ID 为 `qwen3.8-27b`。H3 渠道已随服务停止而禁用，IndexTTS 渠道保持启用。
- new-api 已为该本地模型设置零价格，保留原有模型价格配置与用户令牌。
- new-api 数据库在创建渠道前已备份到部署目录，权限受限；密钥仅保存在服务器，不写入本文。

## 服务参数与接口

- 后端仅监听 `127.0.0.1:8095`，要求 Bearer 鉴权。
- new-api 基础地址：`http://192.168.2.10:3000/v1`。
- 客户端模型名：`qwen3.8-27b`；API 密钥使用 new-api 令牌。
- 默认 16K 上下文、单并发、2 GiB KV 缓存、非思考模式、最多输出 2,048 token。
- 每个请求最多 2 张图片或 1 个视频，视频最多取 8 帧。

文字、图片、OCR 与结构化输出已通过 new-api 实际调用验收。视频参数已配置，本次未做视频推理验收；16K 为配置上限，未进行满上下文压力测试。

## 资源切换

切换前 MiniMax H3 占用约 89.5 GiB GPU 内存，整机仅剩约 18 GiB 可用内存，无法稳定并驻 Qwen 27B。

用户已确认停止 H3。已禁用 new-api 的 H3 渠道（ID 1），停止 `minimax-h3-fl2va` 并将 Docker 自动重启策略从 `always` 改为 `no`。停止后可用内存约 111 GiB；H3 容器、模型、配置和已有生成结果均保留。

Qwen 推理验收已通过，渠道和开机启动均已启用。验收后 Qwen 进程 GPU 内存约 31.34 GiB，整机可用内存约 73 GiB。切换前的渠道状态与 H3 重启策略记录在服务器部署目录的 `switch-before.json`。

服务预检查会在模型未下载完整、H3 仍在运行或可用内存不足 64 GiB 时拒绝启动。

首次启动在采样内核预热时因找不到 `ninja` 失败。已在此服务中补充现有运行时和 CUDA 的 `PATH`、`CUDA_HOME`；FlashInfer 采样内核随后单独验证通过，共享 Python 环境未修改。

## 上线验收

服务器 `download-result.json` 已报告 `complete=true`。`probe.py` 的实际验收结果：

- 后端与 new-api 的未授权请求均返回 401；从当前 Mac 访问内网网关也返回 401。
- 模型列表包含 `qwen3.8-27b`；文字请求回复“连接正常”，通过网关耗时约 0.50 秒。
- 生成图片中的蓝色正方形、绿色圆形与文字 `APM 005` 均被正确识别；返回的 `caption`、`tags`、`visible_text`、`facets` 通过 APM 标注 JSON Schema 校验。该次请求含首次图像内核预热，耗时约 40.73 秒，输出 295 token。
- 搜索规划将“天黑时在海边的照片”拆为“海边”“天黑”，返回 `match=all`，通过 JSON Schema 校验，耗时约 5.04 秒。
- 临时验证令牌仅限此模型、回环 IP、一小时有效且有额度限制，测试后已删除；未访问用户照片。
- Qwen、new-api、IndexTTS 健康检查均为 200。Qwen 后端仅监听 `127.0.0.1:8095`。

上述耗时是单次验收结果，不是吞吐或并发基准。详细结果保存在服务器部署目录的 `probe-results.json`、`final-verification.json` 和 `logs/server.log`。

## 运维与恢复 H3

2026-09-23 后续已新增 [Qwen3.6 NVFP4 常驻服务](xspark-qwen3.6-35b-a3b-nvfp4.md)。恢复 H3 前，需要同时禁用两个 Qwen 渠道（ID 3、4），停止并禁用 `qwen38-27b.service` 和 `qwen36-35b-a3b.service`。

在 xspark 上使用 `systemctl --user status qwen38-27b` 查看状态，使用 `systemctl --user restart qwen38-27b` 重启。日志位于部署目录的 `logs/server.log`。

恢复 H3 时，先禁用 Qwen 渠道并停止、禁用 Qwen 用户服务，再将 H3 Docker 重启策略恢复为记录中的 `always`、启动原容器，等待实际就绪后重新启用 H3 渠道。部署目录的 `new_api_admin.py` 提供 `disable-qwen`、`enable-qwen`、`disable-h3`、`enable-h3` 操作。不要同时启动两套大型模型。H3 原 Compose 文件保留原样，手动重新执行其 Compose 启动可能恢复原重启策略。

来源：[Qwen 官方模型](https://huggingface.co/Qwen/Qwen3.8-27B-FP8)、[官方模型下载说明](https://github.com/QwenLM/Qwen3.8)、[vLLM 模型部署说明](https://recipes.vllm.ai/Qwen/Qwen3.8-27B)。
