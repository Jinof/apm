import json
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent
rows = [r for r in json.loads((ROOT / "summary.json").read_text()) if r["valid_requests"] == 3]
rows.sort(key=lambda r: r["seconds_median"])
names = {
    "qwen38_27b_fp8_eager": "Qwen3.8 27B FP8 · eager",
    "qwen38_27b_fp8_graph": "Qwen3.8 27B FP8 · graph",
    "qwen35_4b_graph": "Qwen3.5 4B BF16",
    "qwen35_9b_graph": "Qwen3.5 9B BF16",
    "qwen36_35b_nvfp4_graph": "Qwen3.6 35B-A3B NVFP4",
    "gemma4_e2b_graph": "Gemma 4 E2B BF16",
}
labels = [names[r["profile"]] for r in rows]
colors = ["#81929E" if r["profile"] == "qwen38_27b_fp8_eager" else "#247F79" for r in rows]
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "svg.fonttype": "none"})
fig, axes = plt.subplots(1, 2, figsize=(12.3, 5.2), gridspec_kw={"width_ratios": [1.4, 1]})
fig.subplots_adjust(left=.245, right=.97, top=.78, bottom=.19, wspace=.26)
fig.text(.04, .94, "APM vision benchmark on xspark", size=19, weight="bold", color="#172E39")
fig.text(.04, .885, "Same synthetic image, 1024 × 683 JPEG · one request at a time · medians of 3 warm runs", color="#536574")
for ax in axes:
    ax.spines[["top", "right", "left"]].set_visible(False)
    ax.set_axisbelow(True); ax.grid(axis="x", alpha=.15)
    ax.tick_params(axis="y", length=0)
    ax.invert_yaxis()
latency = [r["seconds_median"] for r in rows]
error = [[r["seconds_median"] - r["seconds_min"] for r in rows],
         [r["seconds_max"] - r["seconds_median"] for r in rows]]
axes[0].barh(labels, latency, color=colors, height=.58, xerr=error, error_kw={"capsize": 3, "elinewidth": 1})
axes[0].set_xlabel("Complete annotation (seconds) · lower is better", fontsize=9)
axes[0].set_xlim(0, max(latency) * 1.22)
for i, value in enumerate(latency):
    axes[0].text(value + 1, i, f"{value:.1f}s", va="center", fontsize=10)
speeds = [r["decode_tps_median"] for r in rows]
axes[1].barh(range(len(rows)), speeds, color=colors, height=.58)
axes[1].set_yticks(range(len(rows)), [""] * len(rows))
axes[1].set_xlabel("Decode tokens/s · higher is better", fontsize=9)
axes[1].set_xlim(0, max(speeds) * 1.25)
for i, value in enumerate(speeds):
    axes[1].text(value + .5, i, f"{value:.1f}", va="center", fontsize=10)
fig.text(.04, .085, "Error bars: observed min-max (n=3). Warmup excluded. Quality errors are assessed separately in the report.", fontsize=9, color="#536574")
fig.text(.04, .04, "Output lengths and tokenizers differ. Consult the report for exact token counts, OCR, spatial errors and sample-size limits.", fontsize=9, color="#536574")
fig.savefig(ROOT / "comparison.svg", facecolor="white")
fig.savefig(ROOT / "comparison.png", dpi=160, facecolor="white")
print("Saved comparison.svg and comparison.png")
