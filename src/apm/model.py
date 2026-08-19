from __future__ import annotations

import base64
import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from ipaddress import ip_address
from pathlib import Path
from typing import Iterable
from urllib.parse import urlparse


PROMPT_VERSION = "photo-annotation-zh-v3"
DEFAULT_MODEL = "qwen3-vl:4b"
DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434"

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
        "recognized_subjects": {
            "type": "array",
            "maxItems": 20,
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "kind": {"enum": ["人物", "宠物"]},
                },
                "required": ["name", "kind"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["caption", "tags", "visible_text", "facets", "recognized_subjects"],
    "additionalProperties": False,
}

ANNOTATION_PROMPT = """你是私人相册检索系统的照片标注器。只描述图中可见内容，不猜测人物身份、关系、地点、族裔、健康等敏感属性。
人物的男性/女性标签只表示可见外观呈现，不代表实际性别；无法判断时必须输出“不确定”。数量看不清时按明确可见的最小数量记录。
请用中文输出一句客观 caption，并分别给出通用 tags、清晰可辨的 visible_text，以及 facets：
daylight（天亮、天黑、日出日落、室内、不确定之一）、sky、objects（name、正整数 count、attributes）、people（presentation、正整数 count）、actions、scenes、weather。
没有内容的数组使用 []。
recognized_subjects 只在有足够可见依据时从给出的候选名称中选择，kind 只能是“人物”或“宠物”；不确定、没有候选或无法匹配时输出 []，绝不编造名称。
输出必须严格符合给定 JSON Schema，不要添加解释。"""


class ModelError(RuntimeError):
    """The local multimodal model could not produce a valid annotation."""


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
class RecognitionProfile:
    person_names: tuple[str, ...] = ()
    pet_names: tuple[str, ...] = ()

    @classmethod
    def normalize(
        cls,
        person_names: Iterable[str] = (),
        pet_names: Iterable[str] = (),
    ) -> "RecognitionProfile":
        def normalized(values: Iterable[str]) -> tuple[str, ...]:
            result: list[str] = []
            seen: set[str] = set()
            for raw in values:
                value = raw.strip()
                if not value:
                    continue
                if len(value) > 40:
                    raise ValueError("名称最多 40 个字符")
                key = value.casefold()
                if key not in seen:
                    result.append(value)
                    seen.add(key)
            if len(result) > 20:
                raise ValueError("人物或宠物名称最多 20 个")
            return tuple(result)

        people = normalized(person_names)
        pets = normalized(pet_names)
        people_keys = {name.casefold() for name in people}
        if any(name.casefold() in people_keys for name in pets):
            raise ValueError("同一名称不能同时属于人物和宠物")
        return cls(people, pets)


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


class OllamaClient:
    provider = "ollama"
    prompt_version = PROMPT_VERSION

    def __init__(
        self,
        *,
        model: str = DEFAULT_MODEL,
        base_url: str = DEFAULT_OLLAMA_URL,
        timeout_seconds: float = 180.0,
        allow_remote: bool = False,
        person_names: Iterable[str] = (),
        pet_names: Iterable[str] = (),
    ) -> None:
        self.model = model.strip()
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.recognition_profile = RecognitionProfile.normalize(person_names, pet_names)
        if not self.model:
            raise ValueError("模型名称不能为空")
        if not _is_loopback_url(self.base_url) and not allow_remote:
            raise ValueError("非本机模型地址需要显式传入 --allow-remote")
        if _is_local_network_url(self.base_url):
            self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        else:
            self._opener = urllib.request.build_opener()

    def ensure_available(self) -> None:
        try:
            payload = self._request("GET", "/api/tags")
        except ModelError as error:
            raise ModelError(f"无法连接 Ollama: {error}") from error
        names = {
            model.get("name")
            for model in payload.get("models", [])
            if isinstance(model, dict)
        }
        if self.model not in names:
            raise ModelError(
                f"本地尚未安装模型 {self.model!r}；请先运行: ollama pull {self.model}"
            )

    def annotate(self, image_path: Path) -> Annotation:
        try:
            image_bytes = image_path.read_bytes()
        except OSError as error:
            raise ModelError(f"无法读取照片 {image_path}: {error}") from error

        payload = self._request(
            "POST",
            "/api/chat",
            {
                "model": self.model,
                "messages": [
                    {
                        "role": "user",
                        "content": annotation_prompt(self.recognition_profile),
                        "images": [base64.b64encode(image_bytes).decode("ascii")],
                    }
                ],
                "format": annotation_schema(self.recognition_profile),
                "stream": False,
                "options": {"temperature": 0},
            },
        )
        try:
            content = payload["message"]["content"]
            raw_annotation = json.loads(content)
        except (KeyError, TypeError, json.JSONDecodeError) as error:
            raise ModelError("Ollama 返回了无法解析的标注结果") from error
        return validate_annotation(raw_annotation, self.recognition_profile)

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
    ) -> dict[str, object]:
        data = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=data, headers=headers, method=method
        )
        try:
            with self._opener.open(request, timeout=self.timeout_seconds) as response:
                body = response.read()
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise ModelError(str(error)) from error
        try:
            parsed = json.loads(body)
        except json.JSONDecodeError as error:
            raise ModelError("Ollama 返回了非 JSON 响应") from error
        if not isinstance(parsed, dict):
            raise ModelError("Ollama 响应必须是 JSON 对象")
        if "error" in parsed:
            raise ModelError(str(parsed["error"]))
        return parsed


def annotation_prompt(profile: RecognitionProfile) -> str:
    people = "、".join(profile.person_names) or "（无）"
    pets = "、".join(profile.pet_names) or "（无）"
    return f"{ANNOTATION_PROMPT}\n人物候选：{people}\n宠物候选：{pets}"


def annotation_schema(profile: RecognitionProfile) -> dict[str, object]:
    schema = json.loads(json.dumps(ANNOTATION_SCHEMA, ensure_ascii=False))
    candidates = [*profile.person_names, *profile.pet_names]
    recognized = schema["properties"]["recognized_subjects"]
    recognized["maxItems"] = 20 if candidates else 0
    if candidates:
        recognized["items"]["properties"]["name"] = {"enum": candidates}
    return schema


def validate_annotation(
    value: object,
    profile: RecognitionProfile | None = None,
) -> Annotation:
    if not isinstance(value, dict):
        raise ModelError("标注结果必须是 JSON 对象")
    v2_keys = {"caption", "tags", "visible_text", "facets"}
    v3_keys = {*v2_keys, "recognized_subjects"}
    if set(value) != v3_keys and not (profile is None and set(value) == v2_keys):
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
    recognized_subjects = _validate_recognized_subjects(
        value.get("recognized_subjects", []),
        profile,
    )
    return Annotation(
        caption.strip(),
        normalized_tags,
        visible_text.strip(),
        normalized_facets,
        recognized_subjects,
    )


def _validate_recognized_subjects(
    value: object,
    profile: RecognitionProfile | None,
) -> tuple[RecognizedSubject, ...]:
    if not isinstance(value, list) or len(value) > 20:
        raise ModelError("recognized_subjects 必须是最多 20 项的数组")
    people = set(profile.person_names) if profile else None
    pets = set(profile.pet_names) if profile else None
    result: list[RecognizedSubject] = []
    seen: set[tuple[str, str]] = set()
    for index, item in enumerate(value):
        if not isinstance(item, dict) or set(item) != {"name", "kind"}:
            raise ModelError(f"recognized_subjects[{index}] 字段不合法")
        name = item["name"]
        kind = item["kind"]
        if not isinstance(name, str) or not name.strip() or len(name) > 40:
            raise ModelError(f"recognized_subjects[{index}].name 不合法")
        if kind not in {"人物", "宠物"}:
            raise ModelError(f"recognized_subjects[{index}].kind 不合法")
        normalized_name = name.strip()
        if profile is not None:
            allowed = normalized_name in (people if kind == "人物" else pets)
            if not allowed:
                raise ModelError(f"识别名称 {normalized_name} 不在对应的预设候选中")
        key = (kind, normalized_name.casefold())
        if key not in seen:
            result.append(RecognizedSubject(normalized_name, kind))
            seen.add(key)
    return tuple(result)


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
    if daylight not in {"天亮", "天黑", "日出日落", "室内", "不确定"}:
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
        if presentation not in allowed_people:
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
