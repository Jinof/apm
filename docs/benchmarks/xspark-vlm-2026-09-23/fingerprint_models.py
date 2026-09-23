"""Read-only checkpoint fingerprints, run after timed inference completes."""
import hashlib
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MODELS = {
    "Qwen3.5-4B": "Qwen/Qwen3.5-4B",
    "Qwen3.5-9B": "Qwen/Qwen3.5-9B",
    "Qwen3.6-35B-A3B-NVFP4": "nvidia/Qwen3.6-35B-A3B-NVFP4",
    "Qwen3.8-27B-FP8": "Qwen/Qwen3.8-27B-FP8",
    "gemma-4-E2B-it-apm-bench-20260923": "google/gemma-4-E2B-it",
}
records = []
for directory, repo in MODELS.items():
    path = Path("/home/jinof/models") / directory
    revision = None
    cache_metadata = path / ".cache/huggingface/download/config.json.metadata"
    if cache_metadata.exists():
        revision = cache_metadata.read_text().splitlines()[0]
    if "Qwen3.8-27B" in directory:
        revision = "017b9c7af6b5689d5dd426a76e0bc077eb5ca20a"
    if "gemma" in directory:
        revision = "3e22461f65e89153144f8adb70e3b8c2cc9845a7"
    url = "https://huggingface.co/api/models/" + repo
    if revision:
        url += "/revision/" + revision
    with urllib.request.urlopen(url + "?blobs=true", timeout=60) as response:
        remote = json.load(response)
    files = {f["rfilename"]: f for f in remote["siblings"]}
    config = json.loads((path / "config.json").read_text())
    record = {"directory": directory, "repository": repo, "reference_revision": remote["sha"],
              "reference_kind": "recorded download revision" if revision else "current repository reference",
              "architecture": config.get("architectures"), "vision_present": bool(config.get("vision_config")),
              "quantization": config.get("quantization_config"), "files": []}
    generation = path / "generation_config.json"
    record["generation_config"] = json.loads(generation.read_text()) if generation.exists() else None
    for file in sorted(path.glob("*.safetensors")):
        sha = hashlib.sha256()
        with file.open("rb") as stream:
            while block := stream.read(8 * 1024 * 1024):
                sha.update(block)
        digest = sha.hexdigest()
        reference = files.get(file.name, {}).get("lfs", {}).get("sha256")
        record["files"].append({"name": file.name, "bytes": file.stat().st_size,
                                "sha256": digest, "reference_sha256": reference,
                                "matches_reference": digest == reference})
    record["all_weights_match_reference"] = bool(record["files"]) and all(f["matches_reference"] for f in record["files"])
    record["weight_bytes"] = sum(f["bytes"] for f in record["files"])
    record["config_sha256"] = hashlib.sha256((path / "config.json").read_bytes()).hexdigest()
    records.append(record)
    (ROOT / "model-fingerprints.json").write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n")
    print(directory, "reference_match", record["all_weights_match_reference"], "bytes", record["weight_bytes"], flush=True)
