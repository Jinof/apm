"""Temporary, sequential xspark services. Never mutates the production unit."""
import argparse
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PYTHON = "/home/jinof/projects/vllm-runtime/.venv/bin/python"
PROFILES = {
    "qwen35_4b_graph": ("Qwen3.5-4B", "qwen3.5-4b", []),
    "qwen35_9b_graph": ("Qwen3.5-9B", "qwen3.5-9b", []),
    "qwen36_35b_nvfp4_graph": ("Qwen3.6-35B-A3B-NVFP4", "qwen3.6-35b-a3b", ["--moe-backend", "marlin", "--attention-backend", "flashinfer"]),
    "qwen38_27b_fp8_graph": ("Qwen3.8-27B-FP8", "qwen3.8-27b", []),
    "gemma4_e2b_graph": ("gemma-4-E2B-it-apm-bench-20260923", "gemma-4-e2b", []),
}


def status(unit):
    return subprocess.run(["systemctl", "--user", "show", unit, "-p", "ActiveState", "--value"],
                          text=True, capture_output=True).stdout.strip()


def run_profile(name):
    model_dir, alias, extra = PROFILES[name]
    path = Path("/home/jinof/models") / model_dir
    assert (path / "config.json").exists(), "Missing model: " + str(path)
    result_path = ROOT / "results" / name
    result_path.mkdir(parents=True, exist_ok=False)
    unit = "apm-vlm-bench-" + name
    assert status(unit) not in ("active", "activating"), "Unit already running"
    command = [PYTHON, "-m", "vllm.entrypoints.cli.main", "serve", str(path),
        "--host", "127.0.0.1", "--port", "8096", "--served-model-name", alias,
        "--max-model-len", "16384", "--max-num-seqs", "1", "--max-num-batched-tokens", "2048",
        "--gpu-memory-utilization", "0.30", "--kv-cache-memory-bytes", "2147483648",
        "--compilation-config", '{"mode":0,"cudagraph_mode":"FULL_DECODE_ONLY","cudagraph_capture_sizes":[1]}',
        "--default-chat-template-kwargs", '{"enable_thinking":false}',
        "--limit-mm-per-prompt", '{"image":1,"video":0,"audio":0}' if "gemma" in name else '{"image":1,"video":0}',
        "--mm-processor-cache-gb", "0", *extra]
    if "gemma" not in name:
        command += ["--reasoning-parser", "qwen3"]
    record = {"profile": name, "command": command, "startup_started_at": time.time(),
              "production_retained": True, "runtime": "vLLM 0.28.0", "port": 8096,
              "cuda_graph_mode": "FULL_DECODE_ONLY", "torch_compile": False}
    (result_path / "launch.json").write_text(json.dumps(record, indent=2) + "\n")
    launch = ["systemd-run", "--user", "--unit=" + unit, "--collect", "--service-type=exec",
        "--property=EnvironmentFile=" + str(ROOT / "service.env"),
        "--setenv=VLLM_CACHE_ROOT=" + str(ROOT / "cache" / name / "vllm"),
        "--setenv=TORCHINDUCTOR_CACHE_DIR=" + str(ROOT / "cache" / name / "inductor"),
        "--property=MemoryMax=60G", "--property=MemorySwapMax=0", "--property=CPUQuota=1000%",
        "--property=TimeoutStopSec=20", "--property=KillMode=control-group",
        "--property=StandardOutput=append:" + str(ROOT / "logs" / (name + ".log")),
        "--property=StandardError=append:" + str(ROOT / "logs" / (name + ".log")), *command]
    print("START", name, flush=True)
    started = time.monotonic()
    try:
        subprocess.run(launch, check=True, capture_output=True)
        ready = False
        while time.monotonic() - started < 900:
            if status(unit) not in ("active", "activating"):
                raise RuntimeError("Service exited during startup; inspect profile log")
            try:
                with urllib.request.urlopen("http://127.0.0.1:8096/health", timeout=2) as response:
                    ready = response.status == 200
            except Exception:
                pass
            if ready:
                break
            time.sleep(5)
        if not ready:
            raise TimeoutError("Service did not become ready within 900 seconds")
        record["startup_seconds"] = time.monotonic() - started
        record["gpu_at_ready"] = subprocess.check_output([
            "nvidia-smi", "--query-compute-apps=pid,process_name,used_gpu_memory", "--format=csv"], text=True)
        print("READY", name, round(record["startup_seconds"], 2), flush=True)
        subprocess.run([PYTHON, str(ROOT / "benchmark.py"), "--profile", name, "--model", alias,
                        "--base", "http://127.0.0.1:8096", "--key-file", str(ROOT / "service.env")], check=True)
        record["complete"] = True
    except Exception as exc:
        record["complete"] = False
        record["error"] = str(exc)[:500]
        print("FAILED", name, record["error"], flush=True)
    finally:
        subprocess.run(["systemctl", "--user", "stop", unit], capture_output=True)
        record["unit_state_after_cleanup"] = status(unit)
        (result_path / "launch.json").write_text(json.dumps(record, indent=2) + "\n")
        print("STOPPED", name, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("profiles", nargs="+", choices=list(PROFILES))
    args = parser.parse_args()
    for name in args.profiles:
        run_profile(name)
