from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from apm.db import connect, initialize, insert_annotation
from apm.model import CountedObject, PhotoFacets, RecognizedSubject
from apm.scanner import scan_library, utc_now


class APMSearchSkillTest(unittest.TestCase):
    def test_skill_executes_bounded_read_only_intersection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            photo = root / "night-dog.jpg"
            photo.write_bytes(b"skill-test-photo")
            database_path = root / "apm.sqlite3"
            connection = connect(database_path)
            initialize(connection)
            scan_library(connection, root)
            photo_id = connection.execute("SELECT id FROM photo_assets").fetchone()[0]
            insert_annotation(
                connection,
                photo_id=photo_id,
                caption="夜晚的公园里有旺财",
                tags=("夜晚", "公园"),
                facets=PhotoFacets(
                    daylight="天黑",
                    sky=(),
                    objects=(CountedObject("狗", 1, ()),),
                    people=(),
                    actions=("散步",),
                    scenes=("公园",),
                    weather=(),
                ),
                visible_text="",
                model_provider="ollama",
                model_name="test-vl",
                prompt_version="photo-annotation-zh-v3",
                created_at=utc_now(),
                recognized_subjects=(RecognizedSubject("旺财", "宠物"),),
            )
            connection.close()
            script = Path(__file__).parents[1] / "skills" / "apm-search" / "scripts" / "search.py"

            completed = subprocess.run(
                [
                    sys.executable,
                    str(script),
                    "--db",
                    str(database_path),
                    "--query",
                    "天黑",
                    "--query",
                    "旺财",
                    "--match",
                    "all",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            payload = json.loads(completed.stdout)

            self.assertTrue(payload["ok"])
            self.assertEqual(payload["count"], 1)
            self.assertEqual([item["query"] for item in payload["invocations"]], ["天黑", "旺财"])
            self.assertEqual(payload["results"][0]["recognized_subjects"][0]["name"], "旺财")


if __name__ == "__main__":
    unittest.main()
