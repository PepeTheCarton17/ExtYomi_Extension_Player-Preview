"""Optional ExtYomi device-sync relay.

Only ciphertext is stored; a mailbox is cleared as soon as the peer polls it.
Run with: python3 server.py --port 8789

The app's "Servidor relay" field should point to http://<this-host>:8789
"""

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse

MAILBOX_TTL = 15 * 60
mailboxes = {}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def _reply(self, code, body):
        raw = body.encode("utf-8")
        self.send_response(code)
        self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_OPTIONS(self):
        self._reply(204, "")

    def do_POST(self):
        path = urlparse(self.path).path
        if not path.startswith(("/pairing/", "/sync/")):
            self._reply(404, "[]")
            return
        code = unquote(path.split("/", 2)[2])
        if not code.isdigit() or len(code) != 8:
            self._reply(400, "[]")
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", "replace")
        with lock:
            bucket = mailboxes.setdefault(code, [])
            bucket.append((time.time(), body))
        self._reply(200, "{}")

    def do_GET(self):
        path = urlparse(self.path).path
        if not path.startswith(("/pairing/", "/sync/")):
            self._reply(404, "[]")
            return
        code = unquote(path.split("/", 2)[2])
        now = time.time()
        with lock:
            bucket = mailboxes.pop(code, [])
            messages = [body for stamp, body in bucket if now - stamp < MAILBOX_TTL]
        self._reply(200, json.dumps(messages))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8789)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"ExtYomi relay listening on 0.0.0.0:{args.port}")
    server.serve_forever()
