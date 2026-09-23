#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
from dataclasses import asdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO_ROOT / "src"))

from apm.db import search  # noqa: E402


def default_db_path() -> Path:
    configured = os.environ.get("APM_DB")
    return Path(configured).expanduser() if configured else Path.home() / ".apm" / "apm.sqlite3"


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description="Read-only Agent search for an APM photo index")
    value.add_argument("--db", type=Path, default=default_db_path())
    value.add_argument("--query", action="append", required=True)
    value.add_argument("--match", choices=("all", "any"), default="all")
    value.add_argument("--limit", type=int, default=20)
    return value


def connect_read_only(path: Path) -> sqlite3.Connection:
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise ValueError(f"APM 索引不存在: {resolved}")
    connection = sqlite3.connect(f"file:{resolved}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only = ON")
    return connection


def normalize_queries(values: list[str]) -> list[str]:
    queries: list[str] = []
    seen: set[str] = set()
    for value in values:
        query = value.strip()
        key = query.casefold()
        if query and key not in seen:
            queries.append(query)
            seen.add(key)
    if not 1 <= len(queries) <= 4:
        raise ValueError("需要 1 到 4 个非空搜索词")
    if any(len(query) > 80 for query in queries):
        raise ValueError("单个搜索词最多 80 个字符")
    return queries


def merge(groups: list[list[object]], match: str) -> list[object]:
    if not groups:
        return []
    if match == "all":
        required = [{item.photo_id for item in group} for group in groups[1:]]
        return [
            item
            for item in groups[0]
            if all(item.photo_id in photo_ids for photo_ids in required)
        ]
    merged: dict[str, object] = {}
    for group in groups:
        for item in group:
            merged.setdefault(item.photo_id, item)
    return list(merged.values())


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        queries = normalize_queries(args.query)
        if not 1 <= args.limit <= 100:
            raise ValueError("limit 必须在 1 到 100 之间")
        connection = connect_read_only(args.db)
        try:
            groups = [search(connection, query, args.limit) for query in queries]
        finally:
            connection.close()
        results = merge(groups, args.match)
        print(
            json.dumps(
                {
                    "ok": True,
                    "match": args.match,
                    "invocations": [
                        {"query": query, "result_count": len(group)}
                        for query, group in zip(queries, groups)
                    ],
                    "count": len(results),
                    "results": [asdict(item) for item in results],
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0
    except (ValueError, sqlite3.DatabaseError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
