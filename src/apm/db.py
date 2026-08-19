from __future__ import annotations

import json
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from .model import PhotoFacets, RecognizedSubject, facet_search_terms, facets_as_dict


SCHEMA = """
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS photo_assets (
    id TEXT PRIMARY KEY CHECK(length(id) = 64),
    media_type TEXT NOT NULL,
    byte_size INTEGER NOT NULL CHECK(byte_size >= 0),
    discovered_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS photo_locations (
    path TEXT PRIMARY KEY,
    photo_id TEXT NOT NULL REFERENCES photo_assets(id),
    root_path TEXT NOT NULL,
    present INTEGER NOT NULL CHECK(present IN (0, 1)),
    modified_nanoseconds INTEGER NOT NULL,
    last_seen_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS photo_locations_photo_id
    ON photo_locations(photo_id);
CREATE INDEX IF NOT EXISTS photo_locations_root_present
    ON photo_locations(root_path, present);

CREATE TABLE IF NOT EXISTS photo_annotations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    photo_id TEXT NOT NULL REFERENCES photo_assets(id),
    caption TEXT NOT NULL,
    tags_json TEXT NOT NULL,
    facets_json TEXT NOT NULL,
    visible_text TEXT NOT NULL,
    recognized_subjects_json TEXT NOT NULL DEFAULT '[]',
    model_provider TEXT NOT NULL,
    model_name TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS photo_annotations_photo_latest
    ON photo_annotations(photo_id, id DESC);
CREATE INDEX IF NOT EXISTS photo_annotations_provenance
    ON photo_annotations(photo_id, model_provider, model_name, prompt_version);

CREATE VIRTUAL TABLE IF NOT EXISTS photo_search USING fts5(
    photo_id UNINDEXED,
    caption,
    tags,
    visible_text,
    tokenize = 'unicode61 remove_diacritics 2'
);
"""


@dataclass(frozen=True)
class SearchResult:
    photo_id: str
    path: str
    caption: str
    tags: tuple[str, ...]
    facets: dict[str, object]
    visible_text: str
    recognized_subjects: tuple[dict[str, str], ...]
    model_provider: str
    model_name: str
    prompt_version: str
    annotated_at: str


def connect(db_path: Path | str) -> sqlite3.Connection:
    path = Path(db_path).expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    return connection


def initialize(connection: sqlite3.Connection) -> None:
    connection.executescript(SCHEMA)
    columns = {
        row["name"]
        for row in connection.execute("PRAGMA table_info(photo_annotations)").fetchall()
    }
    if "facets_json" not in columns:
        default_facets = json.dumps(
            {
                "daylight": "不确定",
                "sky": [],
                "objects": [],
                "people": [],
                "actions": [],
                "scenes": [],
                "weather": [],
            },
            ensure_ascii=False,
        )
        escaped_default = default_facets.replace("'", "''")
        connection.execute(
            "ALTER TABLE photo_annotations "
            f"ADD COLUMN facets_json TEXT NOT NULL DEFAULT '{escaped_default}'"
        )
    if "recognized_subjects_json" not in columns:
        connection.execute(
            "ALTER TABLE photo_annotations "
            "ADD COLUMN recognized_subjects_json TEXT NOT NULL DEFAULT '[]'"
        )
    connection.commit()


def insert_annotation(
    connection: sqlite3.Connection,
    *,
    photo_id: str,
    caption: str,
    tags: Iterable[str],
    facets: PhotoFacets,
    visible_text: str,
    model_provider: str,
    model_name: str,
    prompt_version: str,
    created_at: str,
    recognized_subjects: Iterable[RecognizedSubject] = (),
) -> int:
    normalized_tags = list(tags)
    serialized_facets = facets_as_dict(facets)
    serialized_subjects = [
        {"name": subject.name, "kind": subject.kind} for subject in recognized_subjects
    ]
    index_terms = [
        *normalized_tags,
        *facet_search_terms(facets),
        *(subject["name"] for subject in serialized_subjects),
    ]
    with connection:
        cursor = connection.execute(
            """
            INSERT INTO photo_annotations (
                photo_id, caption, tags_json, facets_json, visible_text, recognized_subjects_json,
                model_provider, model_name, prompt_version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                photo_id,
                caption,
                json.dumps(normalized_tags, ensure_ascii=False),
                json.dumps(serialized_facets, ensure_ascii=False),
                visible_text,
                json.dumps(serialized_subjects, ensure_ascii=False),
                model_provider,
                model_name,
                prompt_version,
                created_at,
            ),
        )
        annotation_id = int(cursor.lastrowid)
        connection.execute("DELETE FROM photo_search WHERE photo_id = ?", (photo_id,))
        connection.execute(
            """
            INSERT INTO photo_search(photo_id, caption, tags, visible_text)
            VALUES (?, ?, ?, ?)
            """,
            (photo_id, caption, " ".join(index_terms), visible_text),
        )
    return annotation_id


def search(connection: sqlite3.Connection, query: str, limit: int = 20) -> list[SearchResult]:
    normalized_query = query.strip()
    if not normalized_query:
        raise ValueError("搜索词不能为空")
    if limit < 1:
        raise ValueError("limit 必须大于 0")

    escaped = (
        normalized_query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    )
    like = f"%{escaped}%"
    latest_sql = """
        WITH latest AS (
            SELECT annotation.*
            FROM photo_annotations AS annotation
            JOIN (
                SELECT photo_id, MAX(id) AS id
                FROM photo_annotations
                GROUP BY photo_id
            ) AS newest ON newest.id = annotation.id
        )
        SELECT photo_id
        FROM latest
        WHERE caption LIKE ? ESCAPE '\\' COLLATE NOCASE
           OR tags_json LIKE ? ESCAPE '\\' COLLATE NOCASE
           OR facets_json LIKE ? ESCAPE '\\' COLLATE NOCASE
           OR visible_text LIKE ? ESCAPE '\\' COLLATE NOCASE
           OR recognized_subjects_json LIKE ? ESCAPE '\\' COLLATE NOCASE
    """
    matching_ids = {
        row["photo_id"]
        for row in connection.execute(latest_sql, (like, like, like, like, like)).fetchall()
    }

    fts_query = _make_fts_query(normalized_query)
    if fts_query:
        matching_ids.update(
            row["photo_id"]
            for row in connection.execute(
                "SELECT photo_id FROM photo_search WHERE photo_search MATCH ?",
                (fts_query,),
            ).fetchall()
        )

    if not matching_ids:
        return []

    placeholders = ",".join("?" for _ in matching_ids)
    rows = connection.execute(
        f"""
        WITH latest AS (
            SELECT annotation.*
            FROM photo_annotations AS annotation
            JOIN (
                SELECT photo_id, MAX(id) AS id
                FROM photo_annotations
                GROUP BY photo_id
            ) AS newest ON newest.id = annotation.id
        )
        SELECT
            latest.photo_id,
            location.path,
            latest.caption,
            latest.tags_json,
            latest.facets_json,
            latest.visible_text,
            latest.recognized_subjects_json,
            latest.model_provider,
            latest.model_name,
            latest.prompt_version,
            latest.created_at
        FROM latest
        JOIN photo_locations AS location ON location.photo_id = latest.photo_id
        WHERE location.present = 1
          AND latest.photo_id IN ({placeholders})
        ORDER BY latest.id DESC, location.path ASC
        LIMIT ?
        """,
        (*sorted(matching_ids), limit),
    ).fetchall()
    return [
        SearchResult(
            photo_id=row["photo_id"],
            path=row["path"],
            caption=row["caption"],
            tags=tuple(json.loads(row["tags_json"])),
            facets=json.loads(row["facets_json"]),
            visible_text=row["visible_text"],
            recognized_subjects=tuple(json.loads(row["recognized_subjects_json"])),
            model_provider=row["model_provider"],
            model_name=row["model_name"],
            prompt_version=row["prompt_version"],
            annotated_at=row["created_at"],
        )
        for row in rows
    ]


def _make_fts_query(query: str) -> str:
    tokens = [token for token in query.split() if token]
    if not tokens:
        return ""
    return " AND ".join(f'"{token.replace(chr(34), chr(34) * 2)}"' for token in tokens)
