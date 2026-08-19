from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import asdict
from pathlib import Path

from .db import connect, initialize, search
from .model import DEFAULT_MODEL, DEFAULT_OLLAMA_URL, ModelError, OllamaClient
from .scanner import scan_library
from .service import tag_photos


def default_db_path() -> Path:
    configured = os.environ.get("APM_DB")
    if configured:
        return Path(configured).expanduser()
    return Path.home() / ".apm" / "apm.sqlite3"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="apm",
        description="本地优先的 AI 照片标注与搜索",
    )
    parser.add_argument(
        "--db",
        type=Path,
        default=default_db_path(),
        help="SQLite 索引路径（默认: ~/.apm/apm.sqlite3）",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("init", help="初始化本地照片索引")

    scan_parser = subparsers.add_parser("scan", help="扫描照片目录，不修改原文件")
    scan_parser.add_argument("root", type=Path, help="要扫描的照片根目录")

    tag_parser = subparsers.add_parser("tag", help="使用多模态模型标注尚未处理的照片")
    tag_parser.add_argument("--model", default=os.environ.get("APM_MODEL", DEFAULT_MODEL))
    tag_parser.add_argument(
        "--ollama-url",
        default=os.environ.get("APM_OLLAMA_URL", DEFAULT_OLLAMA_URL),
    )
    tag_parser.add_argument("--timeout", type=float, default=180.0)
    tag_parser.add_argument("--limit", type=int)
    tag_parser.add_argument("--force", action="store_true", help="追加一次新标注，即使版本相同")
    tag_parser.add_argument(
        "--allow-remote",
        action="store_true",
        help="显式允许把图片发送到非回环 Ollama 地址",
    )
    tag_parser.add_argument(
        "--person-name",
        action="append",
        default=[],
        help="可重复传入的人物候选名称；模型不确定时不会返回名称",
    )
    tag_parser.add_argument(
        "--pet-name",
        action="append",
        default=[],
        help="可重复传入的宠物候选名称；模型不确定时不会返回名称",
    )

    search_parser = subparsers.add_parser("search", help="搜索描述、标签和图片内文字")
    search_parser.add_argument("query", help="自然语言或关键词")
    search_parser.add_argument("--limit", type=int, default=20)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        connection = connect(args.db)
        initialize(connection)
        if args.command == "init":
            _emit({"ok": True, "db": str(Path(args.db).expanduser().resolve())})
            return 0
        if args.command == "scan":
            report = scan_library(connection, args.root)
            _emit({"ok": not report.errors, **asdict(report)})
            return 0 if not report.errors else 2
        if args.command == "tag":
            client = OllamaClient(
                model=args.model,
                base_url=args.ollama_url,
                timeout_seconds=args.timeout,
                allow_remote=args.allow_remote,
                person_names=args.person_name,
                pet_names=args.pet_name,
            )
            client.ensure_available()
            report = tag_photos(
                connection,
                client,
                limit=args.limit,
                force=args.force,
            )
            _emit({"ok": not report.errors, **asdict(report)})
            return 0 if not report.errors else 2
        if args.command == "search":
            results = search(connection, args.query, args.limit)
            _emit(
                {
                    "ok": True,
                    "query": args.query,
                    "count": len(results),
                    "results": [asdict(result) for result in results],
                }
            )
            return 0
        raise AssertionError(f"未知命令: {args.command}")
    except (ValueError, ModelError, OSError) as error:
        _emit({"ok": False, "error": str(error)}, stream=sys.stderr)
        return 1


def _emit(value: object, *, stream: object = sys.stdout) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2), file=stream)


if __name__ == "__main__":
    raise SystemExit(main())
