"""Sahl Sync companion for a single Windows folder."""
import argparse
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import stat
import subprocess
import tempfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit

LIMIT = 224 * 1024 * 1024
MAX_ITEMS = 5000
BLOCK = 64 * 1024
HASH = re.compile(r"[0-9a-f]{64}\Z")
RESERVED = re.compile(r"(?i)(?:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\..*)?\Z")


def parts(name):
    items = name.split("/")
    if len(items) > 32 or any(
        not part or part in {".", ".."} or part.endswith((".", " "))
        or len(part.encode("utf-8")) > 240 or RESERVED.fullmatch(part)
        or any(ord(char) < 32 or char in '\\:*?"<>|' for char in part)
        or part.lower().startswith(("sahl-sync-staging-", "sahl-sync-previous-"))
        or part.lower() == ".sahl-sync-history"
        for part in items
    ):
        raise ValueError("Unsupported file name")
    return items


def checked_stat(path):
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
        raise ValueError("Linked paths are excluded")
    return info


def inspect_file(path):
    info = checked_stat(path)
    if not stat.S_ISREG(info.st_mode) or info.st_size > LIMIT or info.st_nlink > 1:
        raise ValueError("Unsupported file")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    after = checked_stat(path)
    if (info.st_size, info.st_mtime_ns, info.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        raise ValueError("File changed during inspection")
    return {"hash": digest, "size": info.st_size}


def inventory(root):
    files, skipped = {}, []
    seen = set()
    visited = 0

    def walk(folder):
        nonlocal visited
        for entry in sorted(folder.iterdir()):
            visited += 1
            if visited > MAX_ITEMS:
                raise OverflowError("Folder has too many items")
            name = entry.relative_to(root).as_posix()
            try:
                parts(name)
                info = checked_stat(entry)
            except (ValueError, OSError):
                skipped.append(name)
                continue
            key = name.lower()
            if key in seen:
                raise OverflowError("Folder has names differing only by case")
            seen.add(key)
            if stat.S_ISDIR(info.st_mode):
                walk(entry)
            else:
                try:
                    files[name] = inspect_file(entry)
                except (ValueError, OSError):
                    skipped.append(name)

    walk(root)
    return {"files": files, "skipped": len(skipped)}


def destination(root, name, create=False):
    segments = parts(name)
    checked_stat(root)
    folder = root
    for segment in segments[:-1]:
        folder = folder / segment
        try:
            info = checked_stat(folder)
        except FileNotFoundError:
            if not create:
                raise
            folder.mkdir()
            info = checked_stat(folder)
        if not stat.S_ISDIR(info.st_mode):
            raise ValueError("A file blocks the folder")
    path = folder / segments[-1]
    try:
        checked_stat(path)
    except FileNotFoundError:
        pass
    return path


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(30)
        super().setup()

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.handle_request(False)

    def do_PUT(self):
        self.handle_request(True)

    def handle_request(self, upload):
        supplied = self.headers.get("Authorization", "")
        if not hmac.compare_digest(supplied.encode("utf-8"), self.server.authorization):
            self.send_error(401)
            return
        endpoint = urlsplit(self.path)
        try:
            if endpoint.path == "/v1/ping" and not upload:
                self.body(200, b"{}", "application/json")
                return
            if endpoint.path == "/v1/manifest" and not upload:
                data = json.dumps(inventory(self.server.folder), ensure_ascii=False).encode("utf-8")
                self.body(200, data, "application/json")
                return
            if endpoint.path != "/v1/file":
                self.send_error(404)
                return
            names = parse_qs(endpoint.query, strict_parsing=True, max_num_fields=1)
            if set(names) != {"path"} or len(names["path"]) != 1:
                raise ValueError("Invalid path")
            name = names["path"][0]
            if upload:
                self.receive(name)
            else:
                self.send_file(name)
        except FileNotFoundError:
            self.send_error(404)
        except (ValueError, OverflowError, UnicodeError):
            self.send_error(409)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        except OSError:
            self.send_error(500)

    def body(self, status, data, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_file(self, name):
        path = destination(self.server.folder, name)
        info = inspect_file(path)
        if self.headers.get("If-Match") != info["hash"]:
            self.send_error(412)
            return
        with path.open("rb") as stream:
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(info["size"]))
            self.end_headers()
            remaining = info["size"]
            while remaining:
                block = stream.read(min(BLOCK, remaining))
                if not block:
                    break
                self.wfile.write(block)
                remaining -= len(block)

    def receive(self, name):
        length = int(self.headers.get("Content-Length", "-1"))
        digest = self.headers.get("X-Sahl-SHA256", "")
        expected = self.headers.get("If-Match", "")
        if not 0 <= length <= LIMIT or not HASH.fullmatch(digest) or (
            expected != "missing" and not HASH.fullmatch(expected)
        ):
            raise ValueError("Invalid upload")
        path = destination(self.server.folder, name, create=True)
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix="sahl-sync-staging-", delete=False) as stream:
            stage = Path(stream.name)
            try:
                received = hashlib.sha256()
                remaining = length
                while remaining:
                    block = self.rfile.read(min(BLOCK, remaining))
                    if not block:
                        raise ValueError("Incomplete upload")
                    stream.write(block)
                    received.update(block)
                    remaining -= len(block)
                stream.flush()
                os.fsync(stream.fileno())
                stream.close()
                if received.hexdigest() != digest:
                    raise ValueError("Checksum mismatch")
                path = destination(self.server.folder, name)
                try:
                    current = inspect_file(path)["hash"]
                except FileNotFoundError:
                    current = "missing"
                if current != expected:
                    self.send_error(412)
                    return
                if expected == "missing":
                    # Publish without replacing a file created during the upload.
                    os.link(stage, path)
                else:
                    os.replace(stage, path)
                self.body(200, b"{}", "application/json")
            finally:
                stream.close()
                stage.unlink(missing_ok=True)


class Server(HTTPServer):
    def get_request(self):
        socket, address = super().get_request()
        socket.settimeout(10)
        try:
            return self.tls.wrap_socket(socket, server_side=True), address
        except BaseException:
            socket.close()
            raise


def identity(state, folder):
    key = hashlib.sha256(os.fsencode(str(folder).lower())).hexdigest()
    state = state / key
    state.mkdir(mode=0o700, parents=True, exist_ok=True)
    cert, private_key, credential = (state / name for name in ("cert.pem", "key.pem", "pairing.txt"))
    if not cert.is_file() or not private_key.is_file():
        openssl = shutil.which("openssl")
        if not openssl:
            raise SystemExit("Install OpenSSL or add openssl.exe to PATH.")
        subprocess.run(
            [openssl, "req", "-x509", "-newkey", "rsa:3072", "-sha256", "-days", "825",
             "-nodes", "-subj", "/CN=Sahl Sync LAN", "-keyout", str(private_key), "-out", str(cert)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
    if not credential.is_file():
        credential.write_text(secrets.token_hex(32), encoding="ascii")
    token = credential.read_text(encoding="ascii").strip()
    if not HASH.fullmatch(token):
        raise SystemExit("Pairing credential is invalid.")
    fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text(encoding="ascii"))).hexdigest()
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(cert, private_key)
    return tls, fingerprint, token


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--listen", required=True, type=ipaddress.IPv4Address, help="PC's local IPv4 address")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--qr", type=Path, help="Save a pairing QR image (requires qrcode)")
    args = parser.parse_args()
    if not any(args.listen in ipaddress.ip_network(block) for block in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")):
        parser.error("Use a private IPv4 address.")
    if not 1 <= args.port <= 65535:
        parser.error("Port must be between 1 and 65535.")
    folder = args.folder.absolute()
    if not stat.S_ISDIR(checked_stat(folder).st_mode):
        parser.error("Select an existing folder.")
    folder = folder.resolve()
    state = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "SahlSync" / "LAN"
    state = state.resolve()
    if state.is_relative_to(folder):
        parser.error("Select a folder that does not contain Sahl Sync's private settings.")
    tls, fingerprint, token = identity(state, folder)
    with Server((str(args.listen), args.port), Handler) as server:
        server.folder = folder
        server.tls = tls
        server.authorization = ("Bearer " + token).encode("ascii")
        code = f"SAHL1 {args.listen}:{args.port} {fingerprint} {token}"
        print(f"Sharing {folder}", flush=True)
        print(f"Pairing code (keep private):\n{code}", flush=True)
        if args.qr:
            try:
                import qrcode
                image = args.qr.resolve()
                if image.is_relative_to(folder):
                    parser.error("Save the QR outside the folder you share.")
                qrcode.make(code).save(image)
                print(f"Pairing QR: {image}", flush=True)
            except ImportError:
                print("Install qrcode[pil] to create a QR image, or use the pairing code.", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
