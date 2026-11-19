import hashlib
import http.client
import json
from pathlib import Path
import ssl
import tempfile
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

    def test_unsupported_paths(self):
        for path in ("../escape.txt", "notes/../escape.txt", "a:stream", "CON.txt"):
            self.assertEqual(self.put(path, b"blocked", "missing"), 409)
        self.assertFalse((self.folder.parent / "escape.txt").exists())


if __name__ == "__main__":
    unittest.main()
