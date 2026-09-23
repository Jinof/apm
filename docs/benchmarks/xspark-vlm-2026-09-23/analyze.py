"""Summarize immutable raw measurements; apply one documented rubric correction."""
import csv
import json
import re
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LABELS = {
    "qwen38_27b_fp8_eager": "Qwen3.8-27B FP8 / 当前 eager",
    "qwen38_27b_fp8_graph": "Qwen3.8-27B FP8 / CUDA Graph",
    "qwen35_4b_graph": "Qwen3.5-4B BF16 / CUDA Graph",
    "qwen35_9b_graph": "Qwen3.5-9B BF16 / CUDA Graph",
    "qwen36_35b_nvfp4_graph": "Qwen3.6-35B-A3B NVFP4 / CUDA Graph",
    "gemma4_e2b_graph": "Gemma 4 E2B BF16 / CUDA Graph",
}
CORE = {
    "apple": (3, ["苹果"]), "banana": (2, ["香蕉"]), "mug": (1, ["杯"]),
    "notebook": (1, ["笔记本", "记事本", "本子", "书"]), "phone": (1, ["手机", "智能电话"]),
    "spoon": (1, ["勺", "匙"]), "bowl": (1, ["碗", "盘"]), "menu": (1, ["菜单", "牌", "卡片", "价目"]),
}


def norm(value):
    return re.sub(r"[^\w]", "", value.lower())


def apm_quality(parsed):
    objects = parsed.get("facets", {}).get("objects", [])
    core = {}
    for key, (expected_count, aliases) in CORE.items():
        matched = [x for x in objects if any(a in x.get("name", "") for a in aliases)]
        count = sum(x.get("count", 0) for x in matched)
        core[key] = {"correct": count == expected_count, "count": count, "expected_count": expected_count}
    text = norm(parsed.get("visible_text", ""))
    ocr = {s: norm(s) in text for s in ("海边咖啡", "LATTE28", "CAKE18", "APM2026")}
    people = parsed.get("facets", {}).get("people", [])
    return {"core_counts_correct": sum(v["correct"] for v in core.values()), "core_total": len(core),
            "core": core, "ocr_correct": sum(ocr.values()), "ocr_total": len(ocr), "ocr": ocr,
            "unsupported_people_count": sum(x.get("count", 0) for x in people)}


def median(values):
    return round(statistics.median(values), 4) if values else None


summaries = []
flat = []
for profile, label in LABELS.items():
    directory = ROOT / "results" / profile
    if not directory.exists():
        continue
    launches = directory / "launch.json"
    launch = json.loads(launches.read_text()) if launches.exists() else {}
    runs = [json.loads(p.read_text()) for p in sorted(directory.glob("annotation-*.json")) if p.stem != "annotation-warmup"]
    valid = [r for r in runs if r.get("ok")]
    cold_file = directory / "annotation-warmup.json"
    cold = json.loads(cold_file.read_text()) if cold_file.exists() else {}
    row = {"profile": profile, "label": label, "measured_requests": len(runs),
           "valid_requests": len(valid), "startup_seconds": launch.get("startup_seconds"),
           "startup_error": launch.get("error"), "first_request_seconds": cold.get("seconds"),
           "seconds_median": median([r["seconds"] for r in valid]),
           "seconds_min": round(min((r["seconds"] for r in valid), default=0), 4),
           "seconds_max": round(max((r["seconds"] for r in valid), default=0), 4),
           "ttft_seconds_median": median([r["ttft_seconds"] for r in valid]),
           "decode_tps_median": median([r["decode_tokens_per_second"] for r in valid if "decode_tokens_per_second" in r]),
           "completion_tokens_median": median([r["usage"]["completion_tokens"] for r in valid]),
           "content_characters_median": median([len(r["content"]) for r in valid]),
           "prompt_tokens_median": median([r["usage"]["prompt_tokens"] for r in valid]),
           "apm_quality": [apm_quality(r["parsed"]) for r in valid]}
    qa_file = directory / "qa-1.json"
    if qa_file.exists():
        qa = json.loads(qa_file.read_text())
        if qa.get("quality"):
            details = qa["quality"]["details"]
            corrected = json.loads(json.dumps(details))
            review = json.loads((ROOT / "scoring-review.json").read_text())
            for key, override in review["overrides"].items():
                actual = str(details[key]["actual"]).lower()
                corrected[key]["passed"] = any(v in actual for v in override["accepted_contains"])
            row["qa_automatic_correct"] = qa["quality"]["correct"]
            row["qa_reviewed_correct"] = sum(d["passed"] for d in corrected.values())
            row["qa_total"] = len(corrected)
            row["qa_errors"] = {k: d for k, d in corrected.items() if not d["passed"]}
    for r in runs:
        flat.append({"profile": profile, "repetition": r["repetition"], "ok": r["ok"],
                     "seconds": r.get("seconds"), "ttft_seconds": r.get("ttft_seconds"),
                     "decode_tokens_per_second": r.get("decode_tokens_per_second"),
                     "completion_tokens": r.get("usage", {}).get("completion_tokens"),
                     "prompt_tokens": r.get("usage", {}).get("prompt_tokens"),
                     "schema_valid": r.get("schema_valid"), "finish_reason": r.get("finish_reason")})
    summaries.append(row)
(ROOT / "summary.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2) + "\n")
if flat:
    with (ROOT / "timings.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(flat[0])); writer.writeheader(); writer.writerows(flat)
for s in summaries:
    print(json.dumps({k: s.get(k) for k in ("profile", "valid_requests", "seconds_median", "decode_tps_median", "qa_reviewed_correct", "qa_errors", "startup_error")}, ensure_ascii=False))
