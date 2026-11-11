package com.jinof.apm

import java.net.URI

object EndpointPolicy {
    const val DEFAULT_ENDPOINT = ""

    fun validate(config: InferenceConfig): InferenceConfig {
        require(config.concurrency in 1..8) { "并发度需为 1 至 8" }
        val endpoint = config.endpoint.trim().trimEnd('/')
        val model = config.modelName.trim()
        require(model.isNotEmpty()) { "请填写 API 模型名称" }
        val uri = try {
            URI(endpoint)
        } catch (error: Exception) {
            throw IllegalArgumentException("API 地址不是有效 URL")
        }
        require(uri.scheme?.lowercase() in setOf("http", "https")) {
            "API 地址必须使用 http 或 https"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.port in -1..65535 && uri.path.orEmpty().split('/').none { it == ".." || it == "." }) {
            "API 地址必须是无账号、查询参数和片段的绝对地址"
        }
        require(config.allowRemote) { "请允许将照片和搜索请求发送到此 API" }
        require(config.apiKey.length <= 4096 && config.apiKey.all { it.code in 33..126 }) { "API 密钥格式无效" }
        return config.copy(endpoint = endpoint, modelName = model)
    }

    fun isLoopback(config: InferenceConfig): Boolean {
        val host = try {
            URI(config.endpoint).host
        } catch (_: Exception) {
            null
        }
        return host != null && isLoopbackHost(host)
    }

    fun isLocalNetwork(config: InferenceConfig): Boolean {
        val host = try {
            URI(config.endpoint).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        if (isLoopbackHost(host) || host.endsWith(".local")) return true
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254) ||
            octets[0] == 127
    }

    private fun isLoopbackHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
        if (normalized == "localhost" || normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") {
            return true
        }
        val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
        return octets.size == 4 && octets.all { it in 0..255 } && octets[0] == 127
    }
}
