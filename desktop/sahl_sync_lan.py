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

LIMIT = 2048 * 1024 * 1024
MAX_ITEMS = 20000
BLOCK = 256 * 1024
HASHES = {}  # (path, size, mtime, inode) -> hash; skips re-reading unchanged PC files
DELETED = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "SahlSync" / "Deleted"
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
    cached = (str(path), info.st_size, info.st_mtime_ns, info.st_ino)
    digest = HASHES.get(cached)
    if digest is None:
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
    after = checked_stat(path)
    if (info.st_size, info.st_mtime_ns, info.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        raise ValueError("File changed during inspection")
    if len(HASHES) > 200_000:
        HASHES.clear()
    HASHES[cached] = digest
    return {"hash": digest, "size": info.st_size, "modified": info.st_mtime_ns // 1_000_000}


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
            if entry.name.lower().startswith("sahl-sync-staging-"):
                continue
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

    def do_DELETE(self):
        self.handle_request("delete")

    def handle_request(self, upload):
        supplied = self.headers.get("Authorization", "").encode("utf-8")
        routes = getattr(self.server, "shares", None)
        if routes is None:
            routes = {self.server.authorization: self.server.folder}
        self.folder = next((folder for key, folder in routes.items() if hmac.compare_digest(supplied, key)), None)
        if self.folder is None:
            self.send_error(401)
            return
        endpoint = urlsplit(self.path)
        try:
            if endpoint.path == "/v1/ping" and not upload:
                self.body(200, b"{}", "application/json")
                return
            if endpoint.path == "/v1/manifest" and not upload:
                data = json.dumps(dict(inventory(self.folder), resume=1), ensure_ascii=False).encode("utf-8")
                self.body(200, data, "application/json")
                return
            if endpoint.path not in {"/v1/file", "/v1/upload-offset"}:
                self.send_error(404)
                return
            names = parse_qs(endpoint.query, strict_parsing=True, max_num_fields=1)
            if set(names) != {"path"} or len(names["path"]) != 1:
                raise ValueError("Invalid path")
            name = names["path"][0]
            if endpoint.path == "/v1/upload-offset" and not upload:
                self.resume_status(name)
            elif endpoint.path == "/v1/file" and upload == "delete":
                self.remove(name)
            elif endpoint.path == "/v1/file" and upload:
                if self.headers.get("X-Sahl-Total") is None:
                    self.receive(name)
                else:
                    self.resume_upload(name)
            elif not upload:
                self.send_file(name)
            else:
                self.send_error(405)
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
        path = destination(self.folder, name)
        info = inspect_file(path)
        if self.headers.get("If-Match") != info["hash"]:
            self.send_error(412)
            return
        length = info["size"]
        offset = 0
        requested = self.headers.get("Range")
        if requested:
            match = re.fullmatch(r"bytes=([0-9]+)-", requested)
            if not match or not 0 <= int(match[1]) < length:
                self.send_error(416)
                return
            offset = int(match[1])
        with path.open("rb") as stream:
            stream.seek(offset)
            self.send_response(206 if requested else 200)
            self.send_header("Content-Type", "application/octet-stream")
            if requested:
                self.send_header("Content-Range", f"bytes {offset}-{length - 1}/{length}")
            self.send_header("Content-Length", str(length - offset))
            self.end_headers()
            remaining = length - offset
            while remaining:
                block = stream.read(min(BLOCK, remaining))
                if not block:
                    break
                self.wfile.write(block)
                remaining -= len(block)

    def resume_files(self, name, create=False):
        parts(name)
        length = int(self.headers.get("X-Sahl-Total", "-1"))
        digest = self.headers.get("X-Sahl-SHA256", "")
        expected = self.headers.get("If-Match", "")
        if not 0 <= length <= LIMIT or not HASH.fullmatch(digest) or (
            expected != "missing" and not HASH.fullmatch(expected)
        ):
            raise ValueError("Invalid upload")
        folder = self.folder / "sahl-sync-staging-lan"
        if create:
            try:
                folder.mkdir(exist_ok=True)
            except FileExistsError:
                raise ValueError("A file blocks upload staging")
        try:
            if not stat.S_ISDIR(checked_stat(folder).st_mode):
                raise ValueError("A file blocks upload staging")
        except FileNotFoundError:
            pass
        data, info = folder / "partial", folder / "request.json"
        for path in (data, info):
            try:
                file_stat = checked_stat(path)
                if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_nlink > 1:
                    raise ValueError("Invalid upload staging file")
            except FileNotFoundError:
                pass
        ticket = {"name": name, "length": length, "hash": digest, "expected": expected}
        return data, info, ticket

    def resumable_offset(self, data, info, ticket):
        try:
            if json.loads(info.read_text(encoding="utf-8")) == ticket:
                length = data.stat().st_size
                if length <= ticket["length"]:
                    return length
        except (FileNotFoundError, json.JSONDecodeError):
            pass
        return 0

    def resume_status(self, name):
        data, info, ticket = self.resume_files(name)
        offset = self.resumable_offset(data, info, ticket)
        self.body(200, json.dumps({"offset": offset}).encode("ascii"), "application/json")

    def resume_upload(self, name):
        data, info, ticket = self.resume_files(name, create=True)
        start = int(self.headers.get("X-Sahl-Offset", "-1"))
        length = int(self.headers.get("Content-Length", "-1"))
        if not 0 <= start <= ticket["length"] or start + length != ticket["length"] or length < 0:
            raise ValueError("Invalid upload range")
        offset = self.resumable_offset(data, info, ticket)
        if offset != start:
            self.send_error(409)
            return
        if not start:
            # Truncate the previous partial before publishing metadata for this file.
            with data.open("wb"):
                pass
            info.write_text(json.dumps(ticket), encoding="utf-8")
        with data.open("ab", buffering=0) as stream:
            remaining = length
            while remaining:
                block = self.rfile.read1(min(BLOCK, remaining))
                if not block:
                    raise ConnectionResetError("Incomplete upload")
                if stream.write(block) != len(block):
                    raise OSError("Could not write upload checkpoint")
                remaining -= len(block)
            stream.flush()
            os.fsync(stream.fileno())
        if inspect_file(data)["hash"] != ticket["hash"]:
            data.unlink(missing_ok=True)
            info.unlink(missing_ok=True)
            raise ValueError("Checksum mismatch")
        path = destination(self.folder, name, create=True)
        try:
            current = inspect_file(path)["hash"]
        except FileNotFoundError:
            current = "missing"
        if current != ticket["expected"]:
            data.unlink(missing_ok=True)
            info.unlink(missing_ok=True)
            self.send_error(412)
            return
        try:
            self.publish(data, path, current)
        except FileExistsError:
            self.send_error(412)
            return
        data.unlink(missing_ok=True)
        info.unlink(missing_ok=True)
        self.body(200, b"{}", "application/json")

    @staticmethod
    def publish(stage, path, expected):
        if expected != "missing":
            os.replace(stage, path)
        elif os.name == "nt":
            os.rename(stage, path)
        else:
            os.link(stage, path)

    def remove(self, name):
        """Deleted on the phone: move the PC copy to a recovery folder instead of erasing it."""
        expected = self.headers.get("If-Match", "")
        if not HASH.fullmatch(expected):
            raise ValueError("Invalid delete")
        path = destination(self.folder, name)
        if inspect_file(path)["hash"] != expected:
            self.send_error(412)
            return
        import time
        target = DELETED / time.strftime("%Y-%m-%d") / self.folder.name / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            target = target.with_name(f"{int(time.time())}-{target.name}")
        shutil.move(str(path), str(target))
        # Remove folders that are now empty, so a folder deleted on the phone disappears from the PC too.
        parent = path.parent
        while parent != self.folder and parent.is_relative_to(self.folder):
            try:
                parent.rmdir()
            except OSError:
                break
            parent = parent.parent
        self.body(200, b"{}", "application/json")

    def receive(self, name):
        length = int(self.headers.get("Content-Length", "-1"))
        digest = self.headers.get("X-Sahl-SHA256", "")
        expected = self.headers.get("If-Match", "")
        if not 0 <= length <= LIMIT or not HASH.fullmatch(digest) or (
            expected != "missing" and not HASH.fullmatch(expected)
        ):
            raise ValueError("Invalid upload")
        path = destination(self.folder, name, create=True)
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
                path = destination(self.folder, name)
                try:
                    current = inspect_file(path)["hash"]
                except FileNotFoundError:
                    current = "missing"
                if current != expected:
                    self.send_error(412)
                    return
                try:
                    self.publish(stage, path, expected)
                except FileExistsError:
                    self.send_error(412)
                    return
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


def create_certificate(cert, private_key):
    """Creates the PC's private TLS certificate without needing OpenSSL installed."""
    try:
        import datetime
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import NameOID
    except ImportError:
        return False
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Sahl Sync LAN")])
    now = datetime.datetime.now(datetime.timezone.utc)
    certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                   .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
                   .not_valid_after(now + datetime.timedelta(days=825)).sign(key, hashes.SHA256()))
    private_key.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                              serialization.NoEncryption()))
    cert.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    return True


def identity(state, folder):
    key = hashlib.sha256(os.fsencode(str(folder).lower())).hexdigest()
    state = state / key
    state.mkdir(mode=0o700, parents=True, exist_ok=True)
    cert, private_key, credential = (state / name for name in ("cert.pem", "key.pem", "pairing.txt"))
    if not cert.is_file() or not private_key.is_file():
        if not create_certificate(cert, private_key):
            openssl = shutil.which("openssl")
            if not openssl:
                raise SystemExit("Install the Python 'cryptography' package or OpenSSL.")
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


def start_discovery(port, fingerprint):
    """Answers the phone's Wi-Fi broadcast so it can find this PC even after its address changes."""
    import socket
    import threading

    def answer():
        try:
            listener = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("", port))
        except OSError:
            return
        while True:
            try:
                data, sender = listener.recvfrom(256)
                if data.decode("ascii", "ignore").strip() == "SAHL-FIND " + fingerprint:
                    listener.sendto(f"SAHL-HERE {port}".encode("ascii"), sender)
            except OSError:
                continue

    threading.Thread(target=answer, daemon=True).start()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--listen", required=True, type=ipaddress.IPv4Address, help="PC's local IPv4 address")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--share", type=Path, action="append", default=[], help="Additional folder on the same port")
    parser.add_argument("--pairing-file", type=Path, help="Write pairing codes to a private JSON file")
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
    roots = [folder]
    for additional in args.share:
        candidate = additional.absolute()
        if not stat.S_ISDIR(checked_stat(candidate).st_mode):
            parser.error("Select an existing folder.")
        candidate = candidate.resolve()
        if state.is_relative_to(candidate):
            parser.error("Select a folder that does not contain Sahl Sync's private settings.")
        if candidate in roots:
            parser.error("Select each folder only once.")
        roots.append(candidate)
    tls, fingerprint, token = identity(state, folder)
    codes = {}
    shares = {}
    with Server((str(args.listen), args.port), Handler) as server:
        server.tls = tls
        for root in roots:
            secret = token if root == folder else identity(state, root)[2]
            shares[("Bearer " + secret).encode("ascii")] = root
            codes[str(root)] = f"SAHL1 {args.listen}:{args.port} {fingerprint} {secret}"
        server.shares = shares
        if args.pairing_file:
            output = args.pairing_file.resolve()
            if any(output.is_relative_to(root) for root in roots):
                parser.error("Store pairing codes outside the shared folders.")
            output.write_text(json.dumps(codes), encoding="utf-8")
        else:
            for root, code in codes.items():
                print(f"Sharing {root}", flush=True)
                print(f"Pairing code (keep private):\n{code}", flush=True)
        code = codes[str(folder)]
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
        start_discovery(args.port, fingerprint)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
