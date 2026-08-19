from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from apm.db import connect, initialize, search
from apm.model import (
    Annotation,
    CountedObject,
    ModelError,
    OllamaClient,
    PhotoFacets,
    PresentedPeople,
    RecognizedSubject,
    RecognitionProfile,
    annotation_schema,
    validate_annotation,
)
from apm.scanner import scan_library
from apm.service import tag_photos


class FakeClient:
    provider = "ollama"
    model = "test-vl:1b"
    prompt_version = "test-prompt-v1"

    def __init__(self, annotation: Annotation) -> None:
        self.annotation = annotation
        self.calls: list[Path] = []

    def annotate(self, image_path: Path) -> Annotation:
        self.calls.append(image_path)
        return self.annotation


class FailingClient(FakeClient):
    def annotate(self, image_path: Path) -> Annotation:
        raise ModelError("invalid structured output")


class APMTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.connection = connect(self.root / "apm.sqlite3")
        initialize(self.connection)

    def tearDown(self) -> None:
        self.connection.close()
        self.temporary_directory.cleanup()

    def facets(self) -> PhotoFacets:
        return PhotoFacets(
            daylight="天黑",
            sky=("晚霞",),
            objects=(CountedObject("狗", 2, ("棕色",)),),
            people=(PresentedPeople("女性呈现", 1),),
            actions=("散步",),
            scenes=("海边",),
            weather=(),
        )

    def test_scan_deduplicates_content_and_never_changes_originals(self) -> None:
        content = b"fake-image-content"
        first = self.root / "a.jpg"
        second = self.root / "nested" / "b.png"
        second.parent.mkdir()
        first.write_bytes(content)
        second.write_bytes(content)
        before = hashlib.sha256(first.read_bytes()).hexdigest()

        report = scan_library(self.connection, self.root)

        self.assertEqual(report.files_seen, 2)
        self.assertEqual(report.new_assets, 1)
        self.assertEqual(
            self.connection.execute("SELECT COUNT(*) FROM photo_assets").fetchone()[0],
            1,
        )
        self.assertEqual(
            self.connection.execute("SELECT COUNT(*) FROM photo_locations").fetchone()[0],
            2,
        )
        self.assertEqual(hashlib.sha256(first.read_bytes()).hexdigest(), before)

    def test_tag_and_search_chinese_metadata(self) -> None:
        photo = self.root / "sunset.jpg"
        photo.write_bytes(b"sunset-image")
        original_hash = hashlib.sha256(photo.read_bytes()).hexdigest()
        scan_library(self.connection, self.root)
        client = FakeClient(
            Annotation(
                caption="海边的橙色日落",
                tags=("海边", "日落", "橙色"),
                visible_text="SUMMER",
                facets=self.facets(),
                recognized_subjects=(RecognizedSubject("旺财", "宠物"),),
            )
        )

        report = tag_photos(self.connection, client)
        results = search(self.connection, "海边")
        text_results = search(self.connection, "SUMMER")
        count_results = search(self.connection, "两只狗")
        people_results = search(self.connection, "女人")
        named_results = search(self.connection, "旺财")

        self.assertEqual(report.annotated, 1)
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0].path, str(photo.resolve()))
        self.assertEqual(results[0].model_name, client.model)
        self.assertEqual(len(text_results), 1)
        self.assertEqual(len(count_results), 1)
        self.assertEqual(len(people_results), 1)
        self.assertEqual(len(named_results), 1)
        self.assertEqual(named_results[0].recognized_subjects[0]["kind"], "宠物")
        self.assertEqual(hashlib.sha256(photo.read_bytes()).hexdigest(), original_hash)

    def test_same_model_and_prompt_is_skipped_without_force(self) -> None:
        photo = self.root / "photo.jpg"
        photo.write_bytes(b"one")
        scan_library(self.connection, self.root)
        client = FakeClient(Annotation("一张照片", ("照片",), "", self.facets()))

        first = tag_photos(self.connection, client)
        second = tag_photos(self.connection, client)
        forced = tag_photos(self.connection, client, force=True)

        self.assertEqual(first.annotated, 1)
        self.assertEqual(second.selected, 0)
        self.assertEqual(forced.annotated, 1)
        self.assertEqual(
            self.connection.execute("SELECT COUNT(*) FROM photo_annotations").fetchone()[0],
            2,
        )

    def test_invalid_annotation_commits_nothing(self) -> None:
        photo = self.root / "photo.jpg"
        photo.write_bytes(b"one")
        scan_library(self.connection, self.root)
        client = FailingClient(Annotation("unused", (), "", self.facets()))

        report = tag_photos(self.connection, client)

        self.assertEqual(report.annotated, 0)
        self.assertEqual(len(report.errors), 1)
        self.assertEqual(
            self.connection.execute("SELECT COUNT(*) FROM photo_annotations").fetchone()[0],
            0,
        )
        self.assertEqual(
            self.connection.execute("SELECT COUNT(*) FROM photo_search").fetchone()[0],
            0,
        )

    def test_rescan_marks_missing_path_and_search_hides_it(self) -> None:
        photo = self.root / "photo.jpg"
        photo.write_bytes(b"one")
        scan_library(self.connection, self.root)
        client = FakeClient(Annotation("森林散步", ("森林",), "", self.facets()))
        tag_photos(self.connection, client)
        photo.unlink()

        scan_library(self.connection, self.root)

        self.assertEqual(search(self.connection, "森林"), [])
        present = self.connection.execute(
            "SELECT present FROM photo_locations"
        ).fetchone()[0]
        self.assertEqual(present, 0)

    def test_annotation_validation_rejects_schema_drift(self) -> None:
        with self.assertRaises(ModelError):
            validate_annotation(
                {
                    "caption": "照片",
                    "tags": ["照片"],
                    "visible_text": "",
                    "facets": {
                        "daylight": "天亮",
                        "sky": [],
                        "objects": [],
                        "people": [],
                        "actions": [],
                        "scenes": [],
                        "weather": [],
                    },
                    "confidence": 0.9,
                }
            )

    def test_annotation_validation_rejects_zero_object_count(self) -> None:
        with self.assertRaisesRegex(ModelError, "正整数"):
            validate_annotation(
                {
                    "caption": "两只狗",
                    "tags": ["狗"],
                    "visible_text": "",
                    "facets": {
                        "daylight": "天亮",
                        "sky": [],
                        "objects": [{"name": "狗", "count": 0, "attributes": []}],
                        "people": [],
                        "actions": [],
                        "scenes": ["公园"],
                        "weather": [],
                    },
                }
            )

    def test_remote_model_endpoint_requires_explicit_permission(self) -> None:
        with self.assertRaisesRegex(ValueError, "--allow-remote"):
            OllamaClient(base_url="http://192.0.2.2:11434")

        client = OllamaClient(
            base_url="http://192.0.2.2:11434",
            allow_remote=True,
        )
        self.assertEqual(client.base_url, "http://192.0.2.2:11434")

    def test_recognized_subject_must_match_preset_kind(self) -> None:
        profile = RecognitionProfile.normalize(("小明",), ("旺财",))
        payload = {
            "caption": "旺财在公园",
            "tags": ["狗", "公园"],
            "visible_text": "",
            "facets": {
                "daylight": "天亮",
                "sky": [],
                "objects": [{"name": "狗", "count": 1, "attributes": []}],
                "people": [],
                "actions": [],
                "scenes": ["公园"],
                "weather": [],
            },
            "recognized_subjects": [{"name": "旺财", "kind": "宠物"}],
        }

        annotation = validate_annotation(payload, profile)

        self.assertEqual(annotation.recognized_subjects[0].name, "旺财")
        payload["recognized_subjects"] = [{"name": "陌生人", "kind": "人物"}]
        with self.assertRaisesRegex(ModelError, "不在对应的预设候选"):
            validate_annotation(payload, profile)

    def test_empty_profile_schema_forbids_recognized_subjects(self) -> None:
        schema = annotation_schema(RecognitionProfile())

        self.assertEqual(schema["properties"]["recognized_subjects"]["maxItems"], 0)


if __name__ == "__main__":
    unittest.main()
