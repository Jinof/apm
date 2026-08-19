package com.jinof.apm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Locale

data class AgentSearchPlan(
    val summary: String,
    val queries: List<String>,
    val match: String,
)

data class SearchSkillInvocation(
    val query: String,
    val resultCount: Int,
)

data class AgentSearchResult(
    val plan: AgentSearchPlan,
    val invocations: List<SearchSkillInvocation>,
    val photos: List<PhotoCard>,
)

fun interface AgentPlanner {
    fun plan(request: String): AgentSearchPlan
}

interface PhotoSearchSkill {
    val name: String
    val description: String
    fun invoke(query: String, limit: Int = 50): List<PhotoCard>
}

class DatabasePhotoSearchSkill(private val database: ApmDatabase) : PhotoSearchSkill {
    override val name = "search_photos"
    override val description = "按描述、标签、结构化属性和已识别名称，只读搜索当前可访问照片"

    override fun invoke(query: String, limit: Int): List<PhotoCard> = database.search(query, limit)
}

class SearchAgent(
    private val planner: AgentPlanner,
    private val searchSkill: PhotoSearchSkill,
) {
    fun run(request: String): AgentSearchResult {
        val normalizedRequest = request.trim()
        require(normalizedRequest.isNotEmpty()) { "Agent 请求不能为空" }
        require(normalizedRequest.length <= 500) { "Agent 请求最多 500 个字符" }
        val plan = validatePlan(planner.plan(normalizedRequest))
        val executions = plan.queries.map { query -> query to searchSkill.invoke(query, 50) }
        val photos = if (plan.match == "all") intersect(executions.map { it.second }) else union(executions)
        return AgentSearchResult(
            plan = plan,
            invocations = executions.map { SearchSkillInvocation(it.first, it.second.size) },
            photos = photos,
        )
    }

    private fun validatePlan(candidate: AgentSearchPlan): AgentSearchPlan {
        val queries = candidate.queries.map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase(Locale.ROOT) }
        require(queries.size in 1..4) { "Agent 必须规划 1 到 4 个搜索词" }
        require(queries.all { it.length <= 80 }) { "单个搜索词最多 80 个字符" }
        require(candidate.match == "all" || candidate.match == "any") { "Agent match 必须是 all 或 any" }
        return candidate.copy(
            summary = candidate.summary.trim().take(200).ifEmpty { "已规划本地搜索" },
            queries = queries,
        )
    }

    private fun intersect(groups: List<List<PhotoCard>>): List<PhotoCard> {
        if (groups.isEmpty()) return emptyList()
        val requiredIds = groups.drop(1)
            .map { group -> group.mapTo(mutableSetOf(), PhotoCard::photoId) }
        return groups.first().filter { photo -> requiredIds.all { photo.photoId in it } }
            .distinctBy(PhotoCard::photoId)
    }

    private fun union(executions: List<Pair<String, List<PhotoCard>>>): List<PhotoCard> =
        executions.flatMap { it.second }.distinctBy(PhotoCard::photoId)
}

class OllamaSearchPlanner(
    candidateConfig: InferenceConfig,
    private val profile: RecognitionProfile,
) : AgentPlanner {
    private val config = EndpointPolicy.validate(candidateConfig)

    override fun plan(request: String): AgentSearchPlan {
        val people = profile.personNames.joinToString("、").ifEmpty { "无" }
        val pets = profile.petNames.joinToString("、").ifEmpty { "无" }
        val prompt = """你是一个最小化的私人相册搜索 Agent。你只有一个只读工具 search_photos，能按一个短关键词搜索中文描述、标签、天色、天空、物体与数量、人物外观、动作、场景、天气、可见文字和已识别名称。
把用户请求拆成 1 到 4 个原子搜索词。所有条件必须同时满足时 match=all；任一条件即可时 match=any。不要规划扫描、标注、删除、移动或修改操作，不要生成候选集合之外的姓名。
本地已注册人物标签：$people
本地已注册宠物标签：$pets
用户请求：$request"""
        val payload = JSONObject()
            .put("model", config.modelName)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", prompt)),
            )
            .put("format", planSchema())
            .put("stream", false)
            .put("think", false)
            .put(
                "options",
                JSONObject()
                    .put("temperature", 0)
                    .put("num_predict", 128),
            )
        val response = request(payload)
        val message = response.optJSONObject("message")
        val content = sequenceOf(
            message?.optString("content"),
            message?.optString("thinking"),
        ).filterNotNull().firstOrNull(String::isNotBlank)
            ?: throw IllegalStateException("Ollama Agent 响应缺少结构化计划内容")
        val parsed = try {
            JSONObject(content)
        } catch (error: Exception) {
            throw IllegalStateException("Ollama Agent 返回的计划不是 JSON 对象", error)
        }
        require(parsed.keys().asSequence().toSet() == setOf("summary", "queries", "match")) {
            "Ollama Agent 计划字段不合法"
        }
        val queries = parsed.getJSONArray("queries").let { array ->
            buildList {
                for (index in 0 until array.length()) add(array.getString(index))
            }
        }
        return AgentSearchPlan(
            summary = parsed.getString("summary"),
            queries = queries,
            match = parsed.getString("match"),
        )
    }

    private fun request(body: JSONObject): JSONObject {
        val url = URL("${config.endpoint}/api/chat")
        val connection = if (EndpointPolicy.isLocalNetwork(config)) {
            url.openConnection(Proxy.NO_PROXY)
        } else {
            url.openConnection()
        } as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 90_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val parsed = try {
                JSONObject(text)
            } catch (error: Exception) {
                throw IllegalStateException("Ollama Agent 返回了非 JSON 响应（HTTP $status）", error)
            }
            if (status !in 200..299 || parsed.has("error")) {
                throw IllegalStateException(
                    "Ollama Agent 请求失败：${parsed.optString("error").ifBlank { "HTTP $status" }}",
                )
            }
            return parsed
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("无法连接 Agent ${config.endpoint}：${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun planSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("summary", JSONObject().put("type", "string"))
                .put(
                    "queries",
                    JSONObject()
                        .put("type", "array")
                        .put("items", JSONObject().put("type", "string"))
                        .put("minItems", 1)
                        .put("maxItems", 4),
                )
                .put("match", JSONObject().put("enum", JSONArray(listOf("all", "any")))),
        )
        .put("required", JSONArray(listOf("summary", "queries", "match")))
        .put("additionalProperties", false)
}
