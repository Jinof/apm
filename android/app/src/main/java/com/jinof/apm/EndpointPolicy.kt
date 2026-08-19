package com.jinof.apm

import java.net.URI

object EndpointPolicy {
    const val DEFAULT_ENDPOINT = "http://127.0.0.1:11434"

    fun validate(config: InferenceConfig): InferenceConfig {
        val endpoint = config.endpoint.trim().trimEnd('/')
        val model = config.modelName.trim()
        require(model.isNotEmpty()) { "模型名称不能为空" }
        val uri = try {
            URI(endpoint)
        } catch (error: Exception) {
            throw IllegalArgumentException("模型地址不是有效 URL", error)
        }
        require(uri.scheme?.lowercase() in setOf("http", "https")) {
            "模型地址必须使用 http 或 https"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "模型地址必须是无账号、查询参数和片段的绝对地址"
        }
        if (!isLoopbackHost(uri.host) && !config.allowRemote) {
            throw IllegalArgumentException("非本机模型地址需要勾选“允许发送照片到该地址”")
        }
        return InferenceConfig(endpoint, model, config.allowRemote && !isLoopbackHost(uri.host))
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
