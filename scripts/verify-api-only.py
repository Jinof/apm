"""Reject model weights or inference runtimes in a production or test APK."""
from pathlib import Path
import sys
import zipfile


def verify(path: Path) -> None:
    with zipfile.ZipFile(path) as apk:
        forbidden = []
        for name in apk.namelist():
            lower = name.lower()
            if lower.endswith((".onnx", ".tflite", ".safetensors", ".pt", ".pth", ".gguf")):
                forbidden.append(name)
            if lower.endswith(".so") and any(
                token in lower for token in ("onnxruntime", "opencv", "tensorflow", "litert", "llama")
            ):
                forbidden.append(name)
            if lower.endswith(".dex"):
                content = apk.read(name)
                if any(prefix in content for prefix in (
                    b"Lai/onnxruntime/", b"Lorg/opencv/", b"Lorg/tensorflow/lite/",
                    b"Lcom/google/ai/edge/litert/",
                    b"Lcom/jinof/apm/RemoteVisualClient;", b"Lcom/jinof/apm/IdentityActivity;",
                    b"Lcom/jinof/apm/SimilarityActivity;", b"Lcom/jinof/apm/VisualSimilarityIndexer;",
                )):
                    forbidden.append(name + " (retired feature or inference runtime classes)")
        if forbidden:
            raise SystemExit(f"Model code or weights in {path}: {', '.join(forbidden)}")
    print(f"API-only APK verified: {path}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Usage: verify-api-only.py APK [APK ...]")
    for argument in sys.argv[1:]:
        verify(Path(argument))
