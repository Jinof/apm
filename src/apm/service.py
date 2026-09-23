from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from .db import insert_annotation
from .model import Annotation, ModelError
from .scanner import utc_now


class AnnotationClient(Protocol):
    provider: str
    model: str
    prompt_version: str

    def annotate(self, image_path: Path) -> Annotation: ...


@dataclass(frozen=True)
class TagError:
    photo_id: str
    path: str
    message: str


@dataclass(frozen=True)
class TagReport:
    selected: int
    annotated: int
    errors: tuple[TagError, ...]


def tag_photos(
    connection: sqlite3.Connection,
    client: AnnotationClient,
    *,
    limit: int | None = None,
    force: bool = False,
) -> TagReport:
    if limit is not None and limit < 1:
        raise ValueError("limit 必须大于 0")

    provenance_filter = ""
    parameters: list[object] = []
    if not force:
        provenance_filter = """
            AND NOT EXISTS (
                SELECT 1
                FROM photo_annotations AS annotation
                WHERE annotation.photo_id = asset.id
                  AND annotation.model_provider = ?
                  AND annotation.model_name = ?
                  AND annotation.prompt_version = ?
            )
        """
        parameters.extend((client.provider, client.model, client.prompt_version))
    limit_clause = ""
    if limit is not None:
        limit_clause = " LIMIT ?"
        parameters.append(limit)

    rows = connection.execute(
        f"""
        SELECT asset.id AS photo_id, MIN(location.path) AS path
        FROM photo_assets AS asset
        JOIN photo_locations AS location ON location.photo_id = asset.id
        WHERE location.present = 1
        {provenance_filter}
        GROUP BY asset.id
        ORDER BY asset.discovered_at ASC, asset.id ASC
        {limit_clause}
        """,
        parameters,
    ).fetchall()

    errors: list[TagError] = []
    annotated = 0
    for row in rows:
        photo_id = row["photo_id"]
        image_path = Path(row["path"])
        if not image_path.is_file():
            errors.append(TagError(photo_id, str(image_path), "照片路径已不可读，请重新扫描"))
            continue
        try:
            annotation = client.annotate(image_path)
            insert_annotation(
                connection,
                photo_id=photo_id,
                caption=annotation.caption,
                tags=annotation.tags,
                facets=annotation.facets,
                visible_text=annotation.visible_text,
                model_provider=client.provider,
                model_name=client.model,
                prompt_version=client.prompt_version,
                created_at=utc_now(),
                recognized_subjects=annotation.recognized_subjects,
            )
        except (ModelError, OSError, sqlite3.DatabaseError) as error:
            errors.append(TagError(photo_id, str(image_path), str(error)))
            continue
        annotated += 1

    return TagReport(selected=len(rows), annotated=annotated, errors=tuple(errors))
