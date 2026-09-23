"""Run sequential VLM measurements. Keys remain in an external env file."""
import argparse
import base64
import hashlib
import json
import re
import secrets
import time
import urllib.error
import urllib.request
from pathlib import Path
import jsonschema

ROOT = Path(__file__).resolve().parent
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def normalize(value):
    return re.sub(r"[^\w]", "", str(value).lower())


def qa_score(answer):
    truth = json.loads((ROOT / "ground-truth.json").read_text())
    details = {}
    for key, expected in truth.items():
        actual = answer.get(key)
        if isinstance(expected, list):
            passed = isinstance(actual, str) and any(v.lower() in actual.lower() for v in expected)
        elif isinstance(expected, str):
            passed = normalize(actual) == normalize(expected)
        else:
            passed = type(actual) is type(expected) and actual == expected
        details[key] = {"passed": passed, "actual": actual, "expected": expected}
    return {"correct": sum(v["passed"] for v in details.values()), "total": len(details), "details": details}


def measure(args, task, repetition, key):
    schema = json.loads((ROOT / f"{task}-schema.json").read_text())
    prompt = (ROOT / f"{task}-prompt.txt").read_text()
    image = (ROOT / "assets/cafe-apm-1024.jpg").read_bytes()
    image_uuid = secrets.token_urlsafe(32)
    body = {"model": args.model, "stream": True, "stream_options": {"include_usage": True},
            "temperature": 0, "seed": 23, "max_tokens": 1536 if task == "annotation" else 640,
            "chat_template_kwargs": {"enable_thinking": False}, "cache_salt": secrets.token_urlsafe(32),
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "uuid": image_uuid,
                 "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(image).decode()}}
            ]}],
            "response_format": {"type": "json_schema", "json_schema": {
                "name": "photo_" + task, "strict": True, "schema": schema}}}
    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + key}
    request = urllib.request.Request(args.base.rstrip("/") + "/v1/chat/completions",
                                     data=json.dumps(body).encode(), headers=headers)
    started = time.monotonic()
    result = {"profile": args.profile, "model": args.model, "task": task, "repetition": repetition,
              "started_at": time.time(), "warmup": repetition == "warmup", "image_sha256": hashlib.sha256(image).hexdigest(),
              "sampling": {"temperature": 0, "seed": 23, "max_tokens": body["max_tokens"], "enable_thinking": False},
              "cache_isolation": "unique cache_salt and image UUID per request", "stream": True}
    pieces, reasoning, deltas = [], [], []
    first, last = None, None
    usage, finish, done = {}, None, False
    try:
        with OPENER.open(request, timeout=240) as response:
            result["status"] = response.status
            for raw in response:
                line = raw.decode().strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    done = True
                    break
                event = json.loads(data)
                if event.get("error"):
                    raise RuntimeError(str(event["error"])[:300])
                if event.get("usage"):
                    usage = event["usage"]
                for choice in event.get("choices", []):
                    delta = choice.get("delta", {})
                    value = delta.get("content")
                    if value:
                        now = time.monotonic() - started
                        if first is None:
                            first = now
                        last = now
                        pieces.append(value)
                        deltas.append({"seconds": now, "characters": len(value)})
                    reason = delta.get("reasoning") or delta.get("reasoning_content")
                    if reason:
                        reasoning.append(reason)
                    if choice.get("finish_reason"):
                        finish = choice["finish_reason"]
        elapsed = time.monotonic() - started
        content = "".join(pieces)
        result.update(seconds=elapsed, ttft_seconds=first, last_content_seconds=last, usage=usage,
                      finish_reason=finish, stream_done=done, content=content,
                      reasoning="".join(reasoning), deltas=deltas)
        tokens = usage.get("completion_tokens")
        if tokens is not None and first is not None and last > first:
            result["decode_tokens_per_second"] = (tokens - 1) / (last - first)
            result["output_tokens_per_total_second"] = tokens / elapsed
        try:
            parsed = json.loads(content)
            jsonschema.validate(parsed, schema)
            result["schema_valid"] = True
            result["parsed"] = parsed
            if task == "qa":
                result["quality"] = qa_score(parsed)
        except (ValueError, jsonschema.ValidationError) as exc:
            result["schema_valid"] = False
            result["validation_error"] = str(exc)[:250]
        result["ok"] = bool(result.get("schema_valid") and finish == "stop" and done and not reasoning)
    except urllib.error.HTTPError as exc:
        result.update(ok=False, status=exc.code, seconds=time.monotonic() - started,
                      error=exc.read(2000).decode(errors="replace"))
    except Exception as exc:
        result.update(ok=False, seconds=time.monotonic() - started,
                      error=type(exc).__name__ + ": " + str(exc)[:300], partial_content="".join(pieces))
    output = ROOT / "results" / args.profile / f"{task}-{repetition}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise RuntimeError("Refusing to overwrite a measurement: " + str(output))
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    summary = {k: result.get(k) for k in ("profile", "task", "repetition", "ok", "seconds", "ttft_seconds", "decode_tokens_per_second", "error")}
    summary["completion_tokens"] = usage.get("completion_tokens")
    if result.get("quality"):
        summary["quality"] = {k: result["quality"][k] for k in ("correct", "total")}
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.profile):
        parser.error("Invalid profile name")
    env = dict(l.split("=", 1) for l in args.key_file.read_text().splitlines() if "=" in l)
    key = env["VLLM_API_KEY"]
    cold = measure(args, "annotation", "warmup", key)
    if not cold.get("ok"):
        raise SystemExit("Initial request failed; inspect recorded error before continuing")
    for index in range(1, args.repeats + 1):
        measure(args, "annotation", str(index), key)
    measure(args, "qa", "1", key)
