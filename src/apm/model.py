from __future__ import annotations

import base64
import json
import math
import urllib.error
import urllib.request
from dataclasses import dataclass
from ipaddress import ip_address
from pathlib import Path
from urllib.parse import urlparse


PROMPT_VERSION = "photo-annotation-zh-v4"
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
MAX_IMAGE_BYTES = 20 * 1024 * 1024

STRING_ARRAY = {"type": "array", "items": {"type": "string"}}

ANNOTATION_SCHEMA = {
    "type": "object",
    "properties": {
        "caption": {
            "type": "string",
            "description": "一句简洁、客观的中文照片描述",
        },
        "tags": {
            "type": "array",
            "items": {"type": "string"},
            "maxItems": 40,
            "description": "适合搜索的中文概念、场景、物体、活动与可见专有名词",
        },
        "visible_text": {
            "type": "string",
            "description": "照片中清晰可辨的原文；没有则为空字符串",
        },
        "facets": {
            "type": "object",
            "properties": {
                "daylight": {
                    "type": "string",
                    "enum": ["天亮", "天黑", "日出日落", "室内", "不确定"]
                },
                "sky": {**STRING_ARRAY, "maxItems": 10},
                "objects": {
                    "type": "array",
                    "maxItems": 30,
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "count": {"type": "integer", "minimum": 1},
                            "attributes": {**STRING_ARRAY, "maxItems": 10},
                        },
                        "required": ["name", "count", "attributes"],
                        "additionalProperties": False,
                    },
                },
                "people": {
                    "type": "array",
                    "maxItems": 10,
                    "items": {
                        "type": "object",
                        "properties": {
                            "presentation": {
                                "type": "string",
                                "enum": [
                                    "男性呈现",
                                    "女性呈现",
                                    "儿童",
                                    "多人混合",
                                    "不确定",
                                ]
                            },
                            "count": {"type": "integer", "minimum": 1},
                        },
                        "required": ["presentation", "count"],
                        "additionalProperties": False,
                    },
                },
                "actions": {**STRING_ARRAY, "maxItems": 20},
                "scenes": {**STRING_ARRAY, "maxItems": 10},
                "weather": {**STRING_ARRAY, "maxItems": 10},
            },
            "required": [
                "daylight",
                "sky",
                "objects",
                "people",
                "actions",
                "scenes",
                "weather",
            ],
            "additionalProperties": False,
        },
    },
    "required": ["caption", "tags", "visible_text", "facets"],
    "additionalProperties": False,
}

ANNOTATION_PROMPT = """你是私人相册检索系统的照片标注器。只描述图中可见内容，不猜测人物或宠物身份、关系、地点、族裔、健康等敏感属性。
人物的男性/女性标签只表示可见外观呈现，不代表实际性别；无法判断时必须输出“不确定”。数量看不清时按明确可见的最小数量记录。
请用中文输出一句客观 caption，并分别给出通用 tags、清晰可辨的 visible_text，以及 facets：
daylight（天亮、天黑、日出日落、室内、不确定之一）、sky、objects（name、正整数 count、attributes）、people（presentation、正整数 count）、actions、scenes、weather。
没有内容的数组使用 []。
输出必须严格符合给定 JSON Schema，不要添加解释。"""


class ModelError(RuntimeError):
    """The external model API could not produce a valid annotation."""


@dataclass(frozen=True)
class CountedObject:
    name: str
    count: int
    attributes: tuple[str, ...]


@dataclass(frozen=True)
class PresentedPeople:
    presentation: str
    count: int


@dataclass(frozen=True)
class RecognizedSubject:
    name: str
    kind: str


@dataclass(frozen=True)
class PhotoFacets:
    daylight: str
    sky: tuple[str, ...]
    objects: tuple[CountedObject, ...]
    people: tuple[PresentedPeople, ...]
    actions: tuple[str, ...]
    scenes: tuple[str, ...]
    weather: tuple[str, ...]


@dataclass(frozen=True)
class Annotation:
    caption: str
    tags: tuple[str, ...]
    visible_text: str
    facets: PhotoFacets
    recognized_subjects: tuple[RecognizedSubject, ...] = ()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        fp.close()
        raise ModelError("模型 API 返回重定向；请直接配置最终 API 地址")


class ChatCompletionsClient:
    provider = "openai-compatible"
    prompt_version = PROMPT_VERSION

    def __init__(
        self,
        *,
        model: str,
        base_url: str,
        api_key: str = "",
        timeout_seconds: float = 180.0,
        allow_remote: bool = False,
    ) -> None:
        self.model = model.strip()
        self.base_url = _validate_api_url(base_url)
        self._api_key = api_key.strip()
        self.timeout_seconds = timeout_seconds
        if not self.model:
            raise ValueError("请通过 --model 或 APM_MODEL 配置模型名称")
        if any(ord(character) < 33 or ord(character) > 126 for character in self._api_key):
            raise ValueError("API 密钥只能包含不带空格的可打印 ASCII 字符")
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise ValueError("timeout 必须是有限的正数")
        if not _is_loopback_url(self.base_url) and not allow_remote:
            raise ValueError("非本机模型地址需要显式传入 --allow-remote")
        handlers = [_NoRedirect()]
        if _is_local_network_url(self.base_url):
            handlers.append(urllib.request.ProxyHandler({}))
        self._opener = urllib.request.build_opener(*handlers)

    def annotate(self, image_path: Path) -> Annotation:
        try:
            with image_path.open("rb") as image:
                image_bytes = image.read(MAX_IMAGE_BYTES + 1)
        except OSError as error:
            raise ModelError(f"无法读取照片 {image_path}: {error}") from error
        if len(image_bytes) > MAX_IMAGE_BYTES:
            raise ModelError("照片超过 API 请求的 20 MiB 上限")
        media_type = _image_media_type(image_bytes)

        payload = self._request(
            {
                "model": self.model,
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "text",
                                "text": ANNOTATION_PROMPT,
                            },
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:{media_type};base64,{base64.b64encode(image_bytes).decode('ascii')}",
                                },
                            },
                        ],
                    }
                ],
                "response_format": {
                    "type": "json_schema",
                    "json_schema": {
                        "name": "photo_annotation",
                        "strict": True,
                        "schema": annotation_schema(),
                    },
                },
                "stream": False,
            },
        )
        try:
            choice = payload["choices"][0]
            if choice["finish_reason"] != "stop":
                raise ModelError("模型 API 未完整完成标注")
            message = choice["message"]
            if message.get("refusal"):
                raise ModelError("模型 API 拒绝了标注请求")
            content = message["content"]
            if not isinstance(content, str):
                raise ModelError("模型 API 标注内容必须是 JSON 文本")
            raw_annotation = json.loads(content)
        except (KeyError, IndexError, TypeError, AttributeError, ValueError, RecursionError) as error:
            raise ModelError("模型 API 返回了无法解析的标注结果") from error
        return validate_annotation(raw_annotation)

    def _request(self, payload: dict[str, object]) -> dict[str, object]:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        request = urllib.request.Request(
            f"{self.base_url}/chat/completions", data=data, headers=headers, method="POST"
        )
        try:
            with self._opener.open(request, timeout=self.timeout_seconds) as response:
                body = response.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            raise ModelError(f"模型 API 请求失败（HTTP {status}）") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise ModelError("无法连接模型 API，请检查地址、网络和超时设置") from None
        if len(body) > MAX_RESPONSE_BYTES:
            raise ModelError("模型 API 响应超过 4 MiB 上限")
        try:
            parsed = json.loads(body)
        except (ValueError, UnicodeError, RecursionError) as error:
            raise ModelError("模型 API 返回了非 JSON 响应") from error
        if not isinstance(parsed, dict):
            raise ModelError("模型 API 响应必须是 JSON 对象")
        if "error" in parsed:
            raise ModelError("模型 API 返回错误，请检查服务配置和可用额度")
        return parsed


def _validate_api_url(value: str) -> str:
    value = value.strip().rstrip("/")
    if not value:
        raise ValueError("请通过 --api-url 或 APM_API_URL 配置 API 基础地址（包含 /v1）")
    try:
        parsed = urlparse(value)
        invalid = (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or "?" in value
            or "#" in value
            or any(character.isspace() or ord(character) < 32 for character in value)
            or parsed.port == 0
        )
    except ValueError:
        invalid = True
    if invalid:
        raise ValueError("API 基础地址必须是有效的 HTTP(S) URL，不能包含凭证、查询参数或片段")
    return value


def _image_media_type(data: bytes) -> str:
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp"
    raise ModelError("模型 API 仅支持 JPEG、PNG、WebP 或 GIF；其他格式仍可扫描和搜索")


def annotation_schema() -> dict[str, object]:
    return json.loads(json.dumps(ANNOTATION_SCHEMA, ensure_ascii=False))


def validate_annotation(
    value: object,
) -> Annotation:
    if not isinstance(value, dict):
        raise ModelError("标注结果必须是 JSON 对象")
    v2_keys = {"caption", "tags", "visible_text", "facets"}
    if set(value) != v2_keys:
        raise ModelError("标注结果字段与约定不一致")

    caption = value["caption"]
    tags = value["tags"]
    visible_text = value["visible_text"]
    facets = value["facets"]
    if not isinstance(caption, str) or not caption.strip() or len(caption) > 500:
        raise ModelError("caption 必须是 1 到 500 字的字符串")
    normalized_tags = _string_list(tags, "tags", 40)
    if not isinstance(visible_text, str) or len(visible_text) > 4000:
        raise ModelError("visible_text 必须是最多 4000 字的字符串")
    normalized_facets = _validate_facets(facets)
    return Annotation(
        caption.strip(),
        normalized_tags,
        visible_text.strip(),
        normalized_facets,
    )


def facets_as_dict(facets: PhotoFacets) -> dict[str, object]:
    return {
        "daylight": facets.daylight,
        "sky": list(facets.sky),
        "objects": [
            {"name": item.name, "count": item.count, "attributes": list(item.attributes)}
            for item in facets.objects
        ],
        "people": [
            {"presentation": item.presentation, "count": item.count}
            for item in facets.people
        ],
        "actions": list(facets.actions),
        "scenes": list(facets.scenes),
        "weather": list(facets.weather),
    }


def facet_search_terms(facets: PhotoFacets) -> tuple[str, ...]:
    terms = [facets.daylight, *facets.sky, *facets.actions, *facets.scenes, *facets.weather]
    for item in facets.objects:
        terms.extend(
            (
                item.name,
                f"{item.count}只{item.name}",
                f"{item.count}个{item.name}",
                f"{item.name}{item.count}",
                *item.attributes,
            )
        )
        chinese_counts = ("", "一", "两", "三", "四", "五", "六", "七", "八", "九", "十")
        if item.count < len(chinese_counts):
            terms.extend(
                (
                    f"{chinese_counts[item.count]}只{item.name}",
                    f"{chinese_counts[item.count]}个{item.name}",
                )
            )
    for item in facets.people:
        terms.extend((item.presentation, f"{item.presentation}{item.count}"))
        if item.presentation == "男性呈现":
            terms.extend(("男人", "男性"))
        if item.presentation == "女性呈现":
            terms.extend(("女人", "女性"))
    return tuple(terms)


def _validate_facets(value: object) -> PhotoFacets:
    required = {"daylight", "sky", "objects", "people", "actions", "scenes", "weather"}
    if not isinstance(value, dict) or set(value) != required:
        raise ModelError("facets 字段不完整或包含未知字段")
    daylight = value["daylight"]
    if not isinstance(daylight, str) or daylight not in {"天亮", "天黑", "日出日落", "室内", "不确定"}:
        raise ModelError("daylight 取值不合法")
    objects_value = value["objects"]
    if not isinstance(objects_value, list) or len(objects_value) > 30:
        raise ModelError("objects 必须是最多 30 项的数组")
    objects: list[CountedObject] = []
    for index, item in enumerate(objects_value):
        if not isinstance(item, dict) or set(item) != {"name", "count", "attributes"}:
            raise ModelError(f"objects[{index}] 字段不合法")
        name = item["name"]
        count = item["count"]
        if not isinstance(name, str) or not name.strip() or len(name) > 80:
            raise ModelError(f"objects[{index}].name 不合法")
        if not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= 999:
            raise ModelError(f"objects[{index}].count 必须是正整数")
        objects.append(
            CountedObject(name.strip(), count, _string_list(item["attributes"], "attributes", 10))
        )
    people_value = value["people"]
    if not isinstance(people_value, list) or len(people_value) > 10:
        raise ModelError("people 必须是最多 10 项的数组")
    people: list[PresentedPeople] = []
    allowed_people = {"男性呈现", "女性呈现", "儿童", "多人混合", "不确定"}
    for index, item in enumerate(people_value):
        if not isinstance(item, dict) or set(item) != {"presentation", "count"}:
            raise ModelError(f"people[{index}] 字段不合法")
        presentation = item["presentation"]
        count = item["count"]
        if not isinstance(presentation, str) or presentation not in allowed_people:
            raise ModelError(f"people[{index}].presentation 取值不合法")
        if not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= 999:
            raise ModelError(f"people[{index}].count 必须是正整数")
        people.append(PresentedPeople(presentation, count))
    return PhotoFacets(
        daylight=daylight,
        sky=_string_list(value["sky"], "sky", 10),
        objects=tuple(objects),
        people=tuple(people),
        actions=_string_list(value["actions"], "actions", 20),
        scenes=_string_list(value["scenes"], "scenes", 10),
        weather=_string_list(value["weather"], "weather", 10),
    )


def _string_list(value: object, name: str, maximum: int) -> tuple[str, ...]:
    if not isinstance(value, list) or len(value) > maximum or not all(isinstance(item, str) for item in value):
        raise ModelError(f"{name} 必须是最多 {maximum} 项的字符串数组")
    normalized: list[str] = []
    seen: set[str] = set()
    for item in value:
        text = item.strip()
        if len(text) > 100:
            raise ModelError(f"{name} 中的标签不能超过 100 字")
        key = text.casefold()
        if text and key not in seen:
            normalized.append(text)
            seen.add(key)
    return tuple(normalized)


def _is_loopback_url(base_url: str) -> bool:
    parsed = urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return False
    if parsed.hostname.lower() == "localhost":
        return True
    try:
        return ip_address(parsed.hostname).is_loopback
    except ValueError:
        return False


def _is_local_network_url(base_url: str) -> bool:
    parsed = urlparse(base_url)
    if not parsed.hostname:
        return False
    hostname = parsed.hostname.lower()
    if hostname == "localhost" or hostname.endswith(".local"):
        return True
    try:
        address = ip_address(hostname)
    except ValueError:
        return False
    return address.is_loopback or address.is_private or address.is_link_local
