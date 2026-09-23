# 外部 API 契约

APM 只负责请求、响应校验和本地索引。服务运行、模型安装、权重许可和推理质量由接口提供方负责；本仓库不启动或管理模型服务。

## 标注与 Agent

基础地址包含版本路径，例如 `https://api.example.com/v1`。客户端追加 `/chat/completions`，按配置发送可选的 `Authorization: Bearer <key>`，不跟随 HTTP 重定向。API 和模型默认均为空，不进行自动模型发现或下载。

使用 OpenAI 兼容 Chat Completions：

- `model`：明确配置的模型名称。
- `messages`：标注包含 `text` 和 `image_url` 内容，图片为正确 MIME 的 base64 data URL；Agent 只发送文本。
- `response_format`：`type=json_schema`，`json_schema` 包含名称、`strict=true` 和对应 JSON Schema。
- `stream=false`；不发送 Ollama 专有的 `format`、`options` 或 `think`。
- 响应读取 `choices[0].message.content`，拒绝 refusal、截断或缺失内容，并在本地再次验证结构。

协议参考：[Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[图片输入](https://developers.openai.com/api/docs/guides/images-vision)、[结构化输出](https://developers.openai.com/api/docs/guides/structured-outputs)。兼容服务必须实际支持所需字段；仅有相似 URL 不能保证兼容。

Android 首次标注使用最长边 1024 像素、质量 82 的 JPEG；仅在明确的上下文超限错误时重试一次 768 像素缩略图。不附加身份候选、匿名编号或检测框。API 错误不提交不完整标注，也不显示服务端原始错误正文或密钥。

VLM 设置使用设备 Keystore 加密保存。旧版明确保存的 Ollama 基础地址迁移到同一地址的 `/v1` 兼容路径，模型名称和已有许可保留；没有旧配置的新安装保持禁用。

## 0.0.5 的输出边界

新标注仅包含 `caption`、`tags`、`visible_text`、`facets`；结构化属性仍描述物体、人类外观呈现、动作、场景、天气与数量，不识别具体身份。Android 提示词版本为 `photo-annotation-zh-v7`，CLI 为 `photo-annotation-zh-v4`。旧版标注、身份模板和视觉向量保留在数据库中，仅用于历史读取。

照片重复／相似检测、人脸和宠物识别客户端已移除，应用不再调用 `/v1/models`、`/v1/visual`、`/v1/faces` 或 `/v1/pets`。模型相关功能只使用上面的多模态 API。

## 验证范围

自动化验收使用确定性 mock HTTP 服务，覆盖请求格式、鉴权、禁止重定向、响应校验、旧数据保留和设置迁移。它验证客户端合同，不证明任意外部服务的模型质量或部署可用性。历史固定模型对照结果保留在日期明确的验收记录中。

## 设置页连接测试与推荐

多模态模型卡片可直接测试当前未保存的配置，显示运行中、成功或失败。测试期间对应配置不可修改或重复提交；完成后编辑地址、密钥、模型或许可会清除旧结果。测试不会保存设置或修改数据库。

标注测试发送一张内存生成的 64×64 JPEG，使用生产标注 JSON Schema，要求完成且通过本地严格校验，读取超时为 60 秒。它不需要照片权限，不读取照片、姓名或已有标注；该请求可能产生服务商费用。

截至 2026-09-23，推荐模型为：

| 接口 | 建议 | 依据 |
|---|---|---|
| 标注 / Agent | Qwen3.6-35B-A3B NVFP4；推荐填入 `qwen3.6-35b-a3b` | [官方模型说明](https://huggingface.co/nvidia/Qwen3.6-35B-A3B-NVFP4)；xspark 模型直连实测完整标注中位数 10.52 秒，视觉问答 23/23、核心计数 8/8、OCR 4/4，但自由标注仍有空间关系错误，见 [实测报告](benchmarks/xspark-vlm-2026-09-23/README.md) |

`qwen3.6-35b-a3b` 是 xspark 上 NVFP4 服务的模型 ID；其他服务商可能使用不同 ID。图片、OCR、完整标注结构和 APM 搜索规划已通过 new-api 实际调用。图片效果样本为一张合成图，未对私人图库进行质量评测。

xspark 的 API 基础地址为 `http://192.168.2.10:3000/v1`，使用 new-api 中允许对应模型的令牌。2026-09-23 已启用 `qwen3.6-35b-a3b` 渠道和模型开机启动；现有 APM 令牌可直接调用，`qwen3.8-27b` 和 IndexTTS 继续可用。部署参数和验收结果见 [部署记录](xspark-qwen3.6-35b-a3b-nvfp4.md)。点击推荐只填入模型字段，不保存、不更换地址、不授予许可、不发送请求。
