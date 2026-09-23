from __future__ import annotations

import base64
import copy
import io
import json
import os
import tempfile
import threading
import unittest
import urllib.error
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest.mock import Mock, patch

from apm.cli import build_parser, main
from apm.db import connect, initialize, search
from apm.model import (
    MAX_RESPONSE_BYTES,
    ChatCompletionsClient,
    ModelError,
    annotation_schema,
    validate_annotation,
)
from apm.scanner import scan_library
from apm.service import TagReport, tag_photos


def annotation_payload() -> dict:
    return {
        "caption": "公园中的一只狗",
        "tags": ["公园", "狗"],
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
    }


def completion(annotation: object | None = None) -> dict:
    return {
        "choices": [{
            "finish_reason": "stop",
            "message": {
                "role": "assistant",
                "content": json.dumps(annotation if annotation is not None else annotation_payload()),
            },
        }],
    }


class ModelApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.photo = self.root / "photo.jpg"
        self.photo.write_bytes(b"\xff\xd8\xfftest-photo")
        self.client = ChatCompletionsClient(
            model="vision-model",
            base_url="http://127.0.0.1:8000/v1/",
            api_key="test-secret",
        )

    def respond(self, payload: object) -> Mock:
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        opener = Mock()
        opener.open.return_value = io.BytesIO(body)
        self.client._opener = opener
        return opener

    def test_standard_request_has_bearer_schema_and_exact_image_bytes(self) -> None:
        opener = self.respond(completion())
        original = self.photo.read_bytes()

        result = self.client.annotate(self.photo)

        self.assertEqual(result.caption, "公园中的一只狗")
        self.assertEqual(self.photo.read_bytes(), original)
        opener.open.assert_called_once()
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "http://127.0.0.1:8000/v1/chat/completions")
        self.assertEqual(request.method, "POST")
        self.assertEqual(request.get_header("Authorization"), "Bearer test-secret")
        payload = json.loads(request.data)
        self.assertEqual(set(payload), {"model", "messages", "response_format", "stream"})
        self.assertEqual(payload["model"], "vision-model")
        self.assertFalse(payload["stream"])
        self.assertEqual(payload["response_format"], {
            "type": "json_schema",
            "json_schema": {
                "name": "photo_annotation",
                "strict": True,
                "schema": annotation_schema(),
            },
        })
        content = payload["messages"][0]["content"]
        self.assertEqual(content[0]["type"], "text")
        self.assertEqual(content[1]["image_url"]["url"],
                         "data:image/jpeg;base64," + base64.b64encode(original).decode())

    def test_mime_comes_from_file_bytes_not_filename(self) -> None:
        for data, media_type in (
            (b"\x89PNG\r\n\x1a\nfixture", "image/png"),
            (b"GIF89afixture", "image/gif"),
            (b"RIFF\x04\x00\x00\x00WEBPfixture", "image/webp"),
        ):
            with self.subTest(media_type=media_type):
                self.photo.write_bytes(data)
                opener = self.respond(completion())
                self.client.annotate(self.photo)
                payload = json.loads(opener.open.call_args.args[0].data)
                self.assertTrue(payload["messages"][0]["content"][1]["image_url"]["url"]
                                .startswith(f"data:{media_type};base64,"))

    def test_unsupported_or_oversized_photo_is_not_sent(self) -> None:
        opener = self.respond(completion())
        self.photo.write_bytes(b"unsupported-heic")
        with self.assertRaisesRegex(ModelError, "JPEG、PNG、WebP 或 GIF"):
            self.client.annotate(self.photo)
        self.photo.write_bytes(b"\xff\xd8\xfflarge-image")
        with patch("apm.model.MAX_IMAGE_BYTES", 3):
            with self.assertRaisesRegex(ModelError, "上限"):
                self.client.annotate(self.photo)
        opener.open.assert_not_called()

    def test_key_is_optional(self) -> None:
        self.client = ChatCompletionsClient(model="vision", base_url="http://localhost/v1")
        opener = self.respond(completion())
        self.client.annotate(self.photo)
        self.assertIsNone(opener.open.call_args.args[0].get_header("Authorization"))

    def test_invalid_api_configuration_is_rejected(self) -> None:
        for url in ("", "ftp://host/v1", "https://", "https://user:key@host/v1",
                    "https://host/v1?key=secret", "https://host/v1#secret",
                    "https://host:0/v1", "https://host:99999/v1", "https://host /v1",
                    "https://host\n/v1", "http://[bad/v1"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                ChatCompletionsClient(model="vision", base_url=url, allow_remote=True)
        with self.assertRaisesRegex(ValueError, "--model"):
            ChatCompletionsClient(model="", base_url="http://localhost/v1")
        for timeout in (0, -1, float("nan"), float("inf")):
            with self.subTest(timeout=timeout), self.assertRaisesRegex(ValueError, "timeout"):
                ChatCompletionsClient(model="vision", base_url="http://localhost/v1",
                                      timeout_seconds=timeout)
        with self.assertRaisesRegex(ValueError, "API 密钥"):
            ChatCompletionsClient(model="vision", base_url="http://localhost/v1",
                                  api_key="secret\r\ninjected-header")

    def test_malformed_refused_or_incomplete_completions_fail(self) -> None:
        refused = completion()
        refused["choices"][0]["message"]["refusal"] = "private response detail"
        truncated = completion()
        truncated["choices"][0]["finish_reason"] = "length"
        structured_content = completion()
        structured_content["choices"][0]["message"]["content"] = []
        for payload in (b"not json", b"\xff", [], {}, {"choices": []}, {"choices": [None]},
                        {"choices": [{"finish_reason": "stop", "message": []}]},
                        completion([]), completion({}), refused, truncated, structured_content):
            with self.subTest(payload=payload):
                self.respond(payload)
                with self.assertRaises(ModelError):
                    self.client.annotate(self.photo)

    def test_response_size_is_bounded(self) -> None:
        self.respond(b" " * (MAX_RESPONSE_BYTES + 1))
        with self.assertRaisesRegex(ModelError, "4 MiB"):
            self.client.annotate(self.photo)

    def test_failures_do_not_echo_secrets_or_server_response(self) -> None:
        for failure in (
            urllib.error.HTTPError("https://host", 401, "test-secret", {},
                                   io.BytesIO(b"test-secret private server response")),
            urllib.error.URLError("test-secret private server response"),
        ):
            with self.subTest(failure=type(failure).__name__):
                opener = self.respond(completion())
                opener.open.side_effect = failure
                with self.assertRaises(ModelError) as raised:
                    self.client.annotate(self.photo)
                self.assertNotIn("test-secret", str(raised.exception))
                self.assertNotIn("private server response", str(raised.exception))
        self.respond({"error": "test-secret private server response"})
        with self.assertRaises(ModelError) as raised:
            self.client.annotate(self.photo)
        self.assertNotIn("test-secret", str(raised.exception))

    def test_http_redirect_never_forwards_photo_or_key(self) -> None:
        received = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                received.append((self.command, self.path, self.headers.get("Authorization")))
                self.rfile.read(int(self.headers["Content-Length"]))
                self.send_response(302)
                self.send_header("Location", "/forwarded")
                self.end_headers()

            def do_GET(self):
                received.append((self.command, self.path, self.headers.get("Authorization")))
                self.send_response(200)
                self.end_headers()

            def log_message(self, *args):
                pass

        with patch("socket.getfqdn", return_value="localhost"), \
                HTTPServer(("127.0.0.1", 0), Handler) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                client = ChatCompletionsClient(model="vision", api_key="test-secret", timeout_seconds=3,
                                              base_url=f"http://127.0.0.1:{server.server_port}/v1")
                with self.assertRaisesRegex(ModelError, "重定向"):
                    client.annotate(self.photo)
            finally:
                server.shutdown()
                worker.join(timeout=2)
        self.assertEqual(received, [("POST", "/v1/chat/completions", "Bearer test-secret")])

    def test_invalid_api_annotation_preserves_previous_search_and_history(self) -> None:
        connection = connect(self.root / "index.sqlite3")
        self.addCleanup(connection.close)
        with connection:
            initialize(connection)
            scan_library(connection, self.root)
            self.respond(completion())
            self.assertEqual(tag_photos(connection, self.client).annotated, 1)
            before = [tuple(row) for row in connection.execute("SELECT * FROM photo_annotations")]
            broken = annotation_payload()
            broken["facets"]["daylight"] = []
            self.respond(completion(broken))

            report = tag_photos(connection, self.client, force=True)

            self.assertEqual(report.annotated, 0)
            self.assertEqual(len(report.errors), 1)
            self.assertEqual([tuple(row) for row in connection.execute("SELECT * FROM photo_annotations")], before)
            self.assertEqual(len(search(connection, "公园")), 1)

    def test_strict_schema_declares_types_and_all_object_fields(self) -> None:
        def validate_schema(node: dict) -> None:
            self.assertIn("type", node)
            if "enum" in node:
                self.assertEqual(node["type"], "string")
            if node["type"] == "object":
                self.assertFalse(node["additionalProperties"])
                self.assertEqual(set(node["required"]), set(node["properties"]))
                for child in node["properties"].values():
                    validate_schema(child)
            elif node["type"] == "array":
                validate_schema(node["items"])

        validate_schema(annotation_schema())

    def test_invalid_enum_types_are_model_errors(self) -> None:
        base = annotation_payload()
        variants = []
        for invalid in ([], {}):
            value = copy.deepcopy(base)
            value["facets"]["daylight"] = invalid
            variants.append(value)
            value = copy.deepcopy(base)
            value["facets"]["people"] = [{"presentation": invalid, "count": 1}]
            variants.append(value)
            value = copy.deepcopy(base)
            value["recognized_subjects"] = [{"name": "pet", "kind": invalid}]
            variants.append(value)
        for value in variants:
            with self.subTest(value=value), self.assertRaises(ModelError):
                validate_annotation(value)

    def test_cli_configuration_is_explicit_and_key_is_environment_only(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            arguments = build_parser().parse_args(["tag"])
            self.assertEqual(arguments.api_url, "")
            self.assertEqual(arguments.model, "")
        with patch.dict(os.environ, {
            "APM_API_URL": "https://models.example/v1",
            "APM_MODEL": "vision-model",
            "APM_API_KEY": "environment-secret",
        }, clear=True), patch("apm.cli.ChatCompletionsClient") as factory, \
                patch("apm.cli.tag_photos", return_value=TagReport(0, 0, ())) as tag, \
                redirect_stdout(io.StringIO()):
            result = main(["--db", str(self.root / "cli.sqlite3"), "tag", "--allow-remote"])
        self.assertEqual(result, 0)
        factory.assert_called_once_with(model="vision-model", base_url="https://models.example/v1",
                                        api_key="environment-secret", timeout_seconds=180.0,
                                        allow_remote=True)
        factory.return_value.ensure_available.assert_not_called()
        tag.assert_called_once()


if __name__ == "__main__":
    unittest.main()
