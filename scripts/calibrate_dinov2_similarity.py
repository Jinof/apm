#!/usr/bin/env python3
"""Print DINOv2 whole-scene and 4x4 composition scores for real image pairs."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy
import onnxruntime
from PIL import Image, ImageOps


INPUT_SIZE = 224
PATCHES_PER_SIDE = 16
GRID_SIZE = 4
MEAN = numpy.asarray([0.485, 0.456, 0.406], dtype=numpy.float32)
STD = numpy.asarray([0.229, 0.224, 0.225], dtype=numpy.float32)


def normalize(vector: numpy.ndarray) -> numpy.ndarray:
    norm = numpy.linalg.norm(vector)
    if not numpy.isfinite(norm) or norm <= 0:
        raise ValueError("non-finite or zero embedding")
    return vector / norm


def letterbox(image: Image.Image) -> numpy.ndarray:
    source = ImageOps.exif_transpose(image).convert("RGB")
    source.thumbnail((INPUT_SIZE, INPUT_SIZE), Image.Resampling.BILINEAR)
    canvas = Image.new("RGB", (INPUT_SIZE, INPUT_SIZE), (124, 116, 104))
    canvas.paste(source, ((INPUT_SIZE - source.width) // 2, (INPUT_SIZE - source.height) // 2))
    values = numpy.asarray(canvas, dtype=numpy.float32) / 255.0
    return numpy.transpose((values - MEAN) / STD, (2, 0, 1))[None, ...]


def encode(session: onnxruntime.InferenceSession, image: Image.Image) -> tuple[numpy.ndarray, list[numpy.ndarray]]:
    tokens = session.run(["last_hidden_state"], {"pixel_values": letterbox(image)})[0]
    if tokens.shape != (1, 261, 384):
        raise ValueError(f"unexpected model output {tokens.shape}")
    global_embedding = normalize(tokens[0, 0])
    patches = numpy.stack([normalize(vector) for vector in tokens[0, 5:]])
    patches = patches.reshape(PATCHES_PER_SIDE, PATCHES_PER_SIDE, -1)
    cells: list[numpy.ndarray] = []
    per_cell = PATCHES_PER_SIDE // GRID_SIZE
    for row in range(GRID_SIZE):
        for column in range(GRID_SIZE):
            block = patches[
                row * per_cell : (row + 1) * per_cell,
                column * per_cell : (column + 1) * per_cell,
            ]
            cells.append(normalize(block.reshape(-1, block.shape[-1]).mean(axis=0)))
    return global_embedding, cells


def compare(
    left: tuple[numpy.ndarray, list[numpy.ndarray]],
    right: tuple[numpy.ndarray, list[numpy.ndarray]],
) -> dict[str, float]:
    global_similarity = float(numpy.dot(left[0], right[0]).clip(0, 1))
    composition_similarity = float(
        numpy.mean([numpy.dot(a, b).clip(0, 1) for a, b in zip(left[1], right[1])]),
    )
    return {
        "global_similarity": global_similarity,
        "composition_similarity": composition_similarity,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--query", type=Path, required=True)
    parser.add_argument("--different", type=Path, required=True)
    args = parser.parse_args()

    session = onnxruntime.InferenceSession(str(args.model), providers=["CPUExecutionProvider"])
    query_image = Image.open(args.query)
    different_image = Image.open(args.different)
    width, height = query_image.size
    cropped_image = query_image.crop(
        (round(width * 0.05), round(height * 0.05), round(width * 0.95), round(height * 0.95)),
    )
    mirrored_image = ImageOps.mirror(query_image)

    query = encode(session, query_image)
    report = {
        "same_pixels": compare(query, encode(session, query_image.copy())),
        "same_scene_center_crop_90_percent": compare(query, encode(session, cropped_image)),
        "same_scene_horizontal_mirror": compare(query, encode(session, mirrored_image)),
        "different_photo": compare(query, encode(session, different_image)),
    }
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
