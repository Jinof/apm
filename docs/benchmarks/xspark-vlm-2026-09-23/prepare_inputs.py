"""Freeze APM-compatible inputs; original image remains unchanged."""
import hashlib
import json
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent


def string(limit=None):
    return {"type": "string", **({"maxLength": limit} if limit else {})}


def array(items, maximum):
    return {"type": "array", "items": items, "maxItems": maximum}


def obj(properties):
    return {"type": "object", "properties": properties,
            "required": list(properties), "additionalProperties": False}


strings = lambda maximum: array(string(100), maximum)
count = {"type": "integer", "minimum": 1}
schema = obj({
    "caption": string(500), "tags": strings(40), "visible_text": string(512),
    "facets": obj({
        "daylight": {"type": "string", "enum": ["天亮", "天黑", "日出日落", "室内", "不确定"]},
        "sky": strings(10),
        "objects": array(obj({"name": string(80), "count": count, "attributes": strings(10)}), 30),
        "people": array(obj({"presentation": {"type": "string", "enum":
            ["男性呈现", "女性呈现", "儿童", "多人混合", "不确定"]}, "count": count}), 10),
        "actions": strings(20), "scenes": strings(10), "weather": strings(10),
    }),
})
(ROOT / "annotation-schema.json").write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n")

# Frozen from Android AnnotationContract.BASE_PROMPT, photo-annotation-zh-v7.
prompt = """你是私人相册检索系统的照片标注器。只描述图中可见内容，不猜测人物或宠物身份、关系、地点、族裔、健康等敏感属性。
人物的男性/女性标签只表示可见外观呈现，不代表实际性别；无法判断时必须输出“不确定”。数量看不清时按明确可见的最小数量记录。
请用中文输出一句客观 caption，并分别给出：
- tags：通用检索词，不含姓名；
- visible_text：清晰可辨的原文，最多 512 字符，没有则为空字符串；
- facets.daylight：天亮、天黑、日出日落、室内、不确定之一；
- facets.sky：天空现象，如蓝天、云、晚霞；
- facets.objects：物体 name、正整数 count、可见 attributes；
- facets.people：男性呈现、女性呈现、儿童、多人混合、不确定及正整数 count；
- facets.actions：可见动作；facets.scenes：场景；facets.weather：天气；
没有内容的数组使用 []。严格符合 JSON Schema，不添加解释或身份字段。"""
(ROOT / "annotation-prompt.txt").write_text(prompt)

qa_fields = {
    "apple_count": {"type": "integer"}, "banana_count": {"type": "integer"},
    "apple_color": string(), "banana_color": string(), "bowl_color": string(),
    "mug_count": {"type": "integer"}, "mug_color": string(), "mug_handle_direction": string(),
    "notebook_color": string(), "phone_color": string(), "phone_screen_on": {"type": "boolean"},
    "phone_relative_to_notebook": string(), "spoon_relative_to_mug": string(),
    "menu_title": string(), "latte_price_yuan": {"type": "integer"},
    "cake_price_yuan": {"type": "integer"}, "notebook_text": string(),
    "outdoor_scene": string(), "sky": string(), "daylight": string(),
    "people_count": {"type": "integer"}, "animal_count": {"type": "integer"},
    "precise_city": {"type": ["string", "null"]},
}
(ROOT / "qa-schema.json").write_text(json.dumps(obj(qa_fields), ensure_ascii=False, indent=2) + "\n")
(ROOT / "qa-prompt.txt").write_text(
    "只根据图片回答以下字段，用中文短词回答颜色、方向和场景。apple_count/banana_count 是苹果/香蕉数量；"
    "apple_color/banana_color/bowl_color 是它们与碗的颜色；mug_count/mug_color/mug_handle_direction 是杯子的数量、颜色与把手方向；"
    "notebook_color/phone_color 是笔记本与手机颜色；phone_screen_on 表示手机屏幕是否亮着；"
    "phone_relative_to_notebook 是手机在笔记本的哪一边；spoon_relative_to_mug 是勺子在杯子的哪一边；"
    "menu_title 是菜单首行原文；latte_price_yuan/cake_price_yuan 是菜单两种商品的数字价格；notebook_text 抄写封面原文；"
    "outdoor_scene/sky/daylight 描述窗外主要景物、天空及白天或夜晚；people_count/animal_count 统计可见人物和动物。"
    "precise_city 只在图片足以确定具体城市时填写，否则为 null。输出完整 JSON，不解释。")

# Ground truth set after visually inspecting the generated original, before model runs.
truth = {
    "apple_count": 3, "banana_count": 2, "apple_color": ["红", "red"],
    "banana_color": ["黄", "yellow"], "bowl_color": ["白", "white"],
    "mug_count": 1, "mug_color": ["蓝", "blue"], "mug_handle_direction": ["右", "right"],
    "notebook_color": ["橙", "橘", "orange"], "phone_color": ["黑", "black"],
    "phone_screen_on": False, "phone_relative_to_notebook": ["右", "right"],
    "spoon_relative_to_mug": ["前", "front"], "menu_title": "海边咖啡",
    "latte_price_yuan": 28, "cake_price_yuan": 18, "notebook_text": "APM2026",
    "outdoor_scene": ["海", "sea", "ocean"], "sky": ["云", "cloud", "阴"],
    "daylight": ["白天", "天亮", "日间", "day"], "people_count": 0,
    "animal_count": 0, "precise_city": None,
}
(ROOT / "ground-truth.json").write_text(json.dumps(truth, ensure_ascii=False, indent=2) + "\n")

original = ROOT / "assets/cafe-original.png"
im = Image.open(original).convert("RGB")
scale = 1024 / max(im.size)
size = tuple(int(x * scale + 0.5) for x in im.size)
im.resize(size, Image.Resampling.BILINEAR).save(ROOT / "assets/cafe-apm-1024.jpg", quality=82)
manifest = {"original_size": list(im.size), "request_size": list(size), "jpeg_quality": 82,
            "resize": "Pillow bilinear; same dimensions/quality policy as Android, not byte-identical encoding",
            "files": {}}
for p in (original, ROOT / "assets/cafe-apm-1024.jpg"):
    manifest["files"][p.name] = {"bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
(ROOT / "input-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest))
