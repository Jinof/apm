from __future__ import annotations

import hashlib
import mimetypes
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


SUPPORTED_SUFFIXES = {
    ".avif",
    ".bmp",
    ".gif",
    ".heic",
    ".heif",
    ".jpeg",
    ".jpg",
    ".png",
    ".tif",
    ".tiff",
    ".webp",
}


@dataclass(frozen=True)
class ScanError:
    path: str
    message: str


@dataclass(frozen=True)
class ScanReport:
    root: str
    files_seen: int
    new_assets: int
    locations_upserted: int
    locations_marked_missing: int
    errors: tuple[ScanError, ...]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def scan_library(connection: sqlite3.Connection, root: Path | str) -> ScanReport:
    root_path = Path(root).expanduser().resolve()
    if not root_path.is_dir():
        raise ValueError(f"照片目录不存在或不可读: {root_path}")

    scan_time = utc_now()
    files_seen = 0
    new_assets = 0
    locations_upserted = 0
    errors: list[ScanError] = []

    candidates = sorted(
        path
        for path in root_path.rglob("*")
        if path.is_file()
        and not path.is_symlink()
        and path.suffix.lower() in SUPPORTED_SUFFIXES
    )

    with connection:
        missing_cursor = connection.execute(
            """
            UPDATE photo_locations
            SET present = 0
            WHERE root_path = ? AND present = 1
            """,
            (str(root_path),),
        )
        locations_marked_missing = missing_cursor.rowcount

        for path in candidates:
            normalized_path = path.resolve()
            try:
                stat = normalized_path.stat()
                photo_id = sha256_file(normalized_path)
            except OSError as error:
                errors.append(ScanError(str(normalized_path), str(error)))
                continue

            files_seen += 1
            media_type = mimetypes.guess_type(normalized_path.name)[0] or "image/unknown"
            asset_cursor = connection.execute(
                """
                INSERT OR IGNORE INTO photo_assets (
                    id, media_type, byte_size, discovered_at
                ) VALUES (?, ?, ?, ?)
                """,
                (photo_id, media_type, stat.st_size, scan_time),
            )
            if asset_cursor.rowcount:
                new_assets += 1

            connection.execute(
                """
                INSERT INTO photo_locations (
                    path, photo_id, root_path, present,
                    modified_nanoseconds, last_seen_at
                ) VALUES (?, ?, ?, 1, ?, ?)
                ON CONFLICT(path) DO UPDATE SET
                    photo_id = excluded.photo_id,
                    root_path = excluded.root_path,
                    present = 1,
                    modified_nanoseconds = excluded.modified_nanoseconds,
                    last_seen_at = excluded.last_seen_at
                """,
                (
                    str(normalized_path),
                    photo_id,
                    str(root_path),
                    stat.st_mtime_ns,
                    scan_time,
                ),
            )
            locations_upserted += 1

    return ScanReport(
        root=str(root_path),
        files_seen=files_seen,
        new_assets=new_assets,
        locations_upserted=locations_upserted,
        locations_marked_missing=locations_marked_missing,
        errors=tuple(errors),
    )
