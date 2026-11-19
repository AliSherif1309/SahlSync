import hashlib
import http.client
import json
from pathlib import Path
import ssl
import tempfile
import time
from threading import Thread
import unittest
from urllib.parse import quote

import sahl_sync_lan as lan


class LanCompanionTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        parent = Path(temp.name)
        self.folder = parent / "shared"
        self.folder.mkdir()
        (self.folder / "pc.txt").write_bytes(b"from PC")
        tls, self.fingerprint, self.token = lan.identity(parent / "private", self.folder)
        self.server = lan.Server(("127.0.0.1", 0), lan.Handler)
        self.addCleanup(self.server.server_close)
        self.server.tls, self.server.folder = tls, self.folder
        self.server.authorization = ("Bearer " + self.token).encode("ascii")
        self.client_tls = ssl.create_default_context()
        self.client_tls.load_verify_locations(str(next((parent / "private").glob("*/cert.pem"))))
        self.client_tls.check_hostname = False
        worker = Thread(target=self.server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(worker.join)
        self.addCleanup(self.server.shutdown)

    def request(self, method, route, payload=None, headers=None, paired=True):
        host, port = self.server.server_address
        client = http.client.HTTPSConnection(host, port, context=self.client_tls, timeout=10)
        try:
            client.connect()
            presented = self.digest(client.sock.getpeercert(binary_form=True))
            self.assertEqual(presented, self.fingerprint)
            request_headers = dict(headers or {})
            if paired:
                request_headers["Authorization"] = "Bearer " + self.token
            client.request(method, route, body=payload, headers=request_headers)
            response = client.getresponse()
            return response.status, response.read()
        finally:
            client.close()

    @staticmethod
    def digest(data):
        return hashlib.sha256(data).hexdigest()

    def put(self, path, payload, expected, reported=None, length=None):
        return self.request("PUT", "/v1/file?path=" + quote(path, safe=""), payload,
                            {"If-Match": expected, "X-Sahl-SHA256": reported or self.digest(payload),
                             "Content-Length": str(length if length is not None else len(payload))})[0]

    def test_authenticated_transfers_and_guarded_replacement(self):
        self.assertEqual(self.request("GET", "/v1/ping", paired=False)[0], 401)
        self.assertEqual(self.request("GET", "/v1/ping")[0], 200)
        status, manifest = self.request("GET", "/v1/manifest")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(manifest)["files"]["pc.txt"]["hash"], self.digest(b"from PC"))
        self.assertEqual(self.request("GET", "/v1/file?path=pc.txt",
                                     headers={"If-Match": self.digest(b"from PC")}), (200, b"from PC"))
        self.assertEqual(self.put("notes/phone.txt", b"from phone", "missing"), 200)
        self.assertEqual(self.put("notes/phone.txt", b"updated", self.digest(b"from phone")), 200)
        self.assertEqual(self.put("notes/phone.txt", b"conflict", self.digest(b"from phone")), 412)
        self.assertEqual(self.put("notes/phone.txt", b"bad", self.digest(b"updated"), self.digest(b"expected")), 409)
        self.assertEqual((self.folder / "notes" / "phone.txt").read_bytes(), b"updated")
        self.assertFalse(list(self.folder.rglob("sahl-sync-staging-*")))

    def test_interrupted_upload_keeps_original_file(self):
        host, port = self.server.server_address
        client = http.client.HTTPSConnection(host, port, context=self.client_tls, timeout=10)
        try:
            client.request("PUT", "/v1/file?path=pc.txt", body=b"truncated",
                           headers={"Authorization": "Bearer " + self.token, "Content-Length": "20",
                                    "If-Match": self.digest(b"from PC"), "X-Sahl-SHA256": self.digest(b"whole")})
        finally:
            client.close()
        self.assertEqual(self.request("GET", "/v1/ping")[0], 200)
        self.assertEqual((self.folder / "pc.txt").read_bytes(), b"from PC")
        self.assertFalse(list(self.folder.rglob("sahl-sync-staging-*")))

    def test_separate_pairing_codes_use_separate_folders(self):
        second = self.folder.parent / "second"
        second.mkdir()
        (second / "only-second.txt").write_bytes(b"second")
        second_token = "b" * 64
        self.server.shares = {self.server.authorization: self.folder,
                              ("Bearer " + second_token).encode("ascii"): second}
        status, manifest = self.request("GET", "/v1/manifest")
        self.assertEqual(status, 200)
        self.assertNotIn("only-second.txt", json.loads(manifest)["files"])
        status, manifest = self.request("GET", "/v1/manifest", paired=False,
                                        headers={"Authorization": "Bearer " + second_token})
        self.assertEqual(status, 200)
        self.assertEqual(set(json.loads(manifest)["files"]), {"only-second.txt"})
        self.assertEqual(self.request("GET", "/v1/file?path=only-second.txt",
                                     headers={"If-Match": self.digest(b"second")})[0], 404)

    def test_resume_after_partial_upload(self):
        body = b"this content was interrupted"
        headers = {"X-Sahl-Total": str(len(body)), "If-Match": self.digest(b"from PC"),
                   "X-Sahl-SHA256": self.digest(body)}
        offset_path = "/v1/upload-offset?path=pc.txt"
        self.assertEqual(json.loads(self.request("GET", offset_path, headers=headers)[1])["offset"], 0)
        host, port = self.server.server_address
        client = http.client.HTTPSConnection(host, port, context=self.client_tls, timeout=10)
        try:
            interrupted_headers = dict(headers, **{"Authorization": "Bearer " + self.token,
                                                  "Content-Length": str(len(body)), "X-Sahl-Offset": "0"})
            client.request("PUT", "/v1/file?path=pc.txt", body=body[:7], headers=interrupted_headers)
            partial = self.folder / "sahl-sync-staging-lan" / "partial"
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                if partial.exists() and partial.stat().st_size == 7:
                    break
                time.sleep(0.01)
        finally:
            client.close()
        offset = json.loads(self.request("GET", offset_path, headers=headers)[1])["offset"]
        self.assertEqual(offset, 7)
        self.assertEqual((self.folder / "pc.txt").read_bytes(), b"from PC")
        headers["X-Sahl-Offset"] = str(offset)
        self.assertEqual(self.request("PUT", "/v1/file?path=pc.txt", body[offset:], headers)[0], 200)
        self.assertEqual((self.folder / "pc.txt").read_bytes(), body)
        self.assertEqual(json.loads(self.request("GET", offset_path, headers=headers)[1])["offset"], 0)

    def test_resumable_upload_rechecks_destination(self):
        changed = b"intervening edit"
        (self.folder / "pc.txt").write_bytes(changed)
        body = b"stale update"
        headers = {"X-Sahl-Total": str(len(body)), "If-Match": self.digest(b"from PC"),
                   "X-Sahl-SHA256": self.digest(body), "X-Sahl-Offset": "0"}
        self.assertEqual(self.request("PUT", "/v1/file?path=pc.txt", body, headers)[0], 412)
        self.assertEqual((self.folder / "pc.txt").read_bytes(), changed)

    def test_ranged_download_respects_the_snapshot_hash(self):
        headers = {"If-Match": self.digest(b"from PC"), "Range": "bytes=4-"}
        self.assertEqual(self.request("GET", "/v1/file?path=pc.txt", headers=headers), (206, b" PC"))
        (self.folder / "pc.txt").write_bytes(b"changed")
        self.assertEqual(self.request("GET", "/v1/file?path=pc.txt", headers=headers)[0], 412)

    def test_unsupported_paths(self):
        for path in ("../escape.txt", "notes/../escape.txt", "a:stream", "CON.txt"):
            self.assertEqual(self.put(path, b"blocked", "missing"), 409)
        self.assertFalse((self.folder.parent / "escape.txt").exists())


if __name__ == "__main__":
    unittest.main()
