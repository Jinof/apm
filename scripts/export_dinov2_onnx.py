#!/usr/bin/env python3
"""Export the public DINOv2 ViT-S/14 registers checkpoint for APM."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


MODEL_ID = "facebook/dinov2-with-registers-small"
MODEL_REVISION = "0d9846e56b43a21fa46d7f3f5070f0506a5795a9"
EXPECTED_TOKENS = 261
EXPECTED_DIMENSION = 384


def strip_export_metadata(message: object) -> int:
    """Remove optional protobuf metadata that can retain exporter-local paths."""
    removed = 0
    descriptor = getattr(message, "DESCRIPTOR", None)
    if descriptor is None:
        return removed
    if "doc_string" in descriptor.fields_by_name and getattr(message, "doc_string", ""):
        message.ClearField("doc_string")
        removed += 1
    if "metadata_props" in descriptor.fields_by_name:
        metadata = getattr(message, "metadata_props")
        removed += len(metadata)
        message.ClearField("metadata_props")
    for field, value in list(message.ListFields()):
        if field.type != field.TYPE_MESSAGE:
            continue
        if field.is_repeated:
            for child in value:
                removed += strip_export_metadata(child)
        else:
            removed += strip_export_metadata(value)
    return removed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model-id", default=MODEL_ID)
    parser.add_argument("--revision", default=MODEL_REVISION)
    args = parser.parse_args()

    import numpy
    import onnx
    import onnxruntime
    import torch
    from transformers import AutoModel

    class LastHiddenState(torch.nn.Module):
        def __init__(self, model: torch.nn.Module) -> None:
            super().__init__()
            self.model = model.eval()

        def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
            return self.model(pixel_values=pixel_values).last_hidden_state

    output = args.output.resolve()
    checksum_output = output.with_suffix(output.suffix + ".sha256")
    if output.exists() or checksum_output.exists():
        raise SystemExit(f"Refusing to overwrite existing output: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)

    source_model = AutoModel.from_pretrained(args.model_id, revision=args.revision)
    model = LastHiddenState(source_model)
    sample = torch.zeros((1, 3, 224, 224), dtype=torch.float32)
    with torch.no_grad():
        reference = model(sample)
    expected_shape = (1, EXPECTED_TOKENS, EXPECTED_DIMENSION)
    if tuple(reference.shape) != expected_shape:
        raise SystemExit(
            f"Unexpected DINOv2 ViT-S/14 registers output: "
            f"expected {expected_shape}, got {tuple(reference.shape)}",
        )

    torch.onnx.export(
        model,
        (sample,),
        output,
        input_names=["pixel_values"],
        output_names=["last_hidden_state"],
        opset_version=18,
        dynamo=True,
        external_data=False,
    )
    if output.with_suffix(output.suffix + ".data").exists():
        raise SystemExit("Export unexpectedly produced external tensor data; Android requires one ONNX file")

    onnx_model = onnx.load(output)
    stripped_metadata_count = strip_export_metadata(onnx_model)
    onnx.save(onnx_model, output)
    onnx_model = onnx.load(output)
    onnx.checker.check_model(onnx_model)
    session = onnxruntime.InferenceSession(str(output), providers=["CPUExecutionProvider"])
    actual = session.run(["last_hidden_state"], {"pixel_values": sample.numpy()})[0]
    expected = reference.detach().numpy()
    if actual.shape != expected.shape:
        raise SystemExit(f"ONNX output shape mismatch: expected {expected.shape}, got {actual.shape}")
    maximum_error = float(numpy.max(numpy.abs(actual - expected)))
    if not numpy.allclose(actual, expected, atol=1e-4, rtol=1e-4):
        raise SystemExit(f"ONNX output differs from PyTorch: max_abs_error={maximum_error}")

    checksum = hashlib.sha256(output.read_bytes()).hexdigest()
    checksum_output.write_text(f"{checksum}\n", encoding="ascii")
    print(f"Exported {output}")
    print(f"model={args.model_id} revision={args.revision}")
    print(
        f"tokens={reference.shape[1]} dimension={reference.shape[2]} "
        f"max_abs_error={maximum_error} stripped_metadata={stripped_metadata_count} "
        f"sha256={checksum}",
    )


if __name__ == "__main__":
    main()
