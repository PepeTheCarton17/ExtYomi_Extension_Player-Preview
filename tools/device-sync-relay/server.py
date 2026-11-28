"""Optional ExtYomi device-sync relay.

Only ciphertext is stored. A mailbox belongs to ONE recipient: the sender writes to
``/{kind}/{code}/{recipient}`` and only that recipient's GET drains it. A single mailbox
per code used to let a device consume and discard its own messages, which made sync
look like it worked once and then stopped forever.

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
# (kind, code, recipient) -> [(timestamp, body)]
mailboxes = {}
lock = threading.Lock()


def parse_path(path):
    """Return (kind, code, recipient) for a valid mailbox path, else None."""
    parts = unquote(urlparse(path).path).strip("/").split("/")
    if len(parts) != 3 or parts[0] not in ("pairing", "sync"):
        return None
    kind, code, recipient = parts
    if not code.isdigit() or len(code) != 8 or not recipient:
        return None
    return kind, code, recipient


def sweep(now):
    for key in list(mailboxes):
        fresh = [(stamp, body) for stamp, body in mailboxes[key] if now - stamp < MAILBOX_TTL]
        if fresh:
            mailboxes[key] = fresh
        else:
            mailboxes.pop(key, None)


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
        target = parse_path(self.path)
        if target is None:
            self._reply(400, "[]")
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", "replace")
        with lock:
            mailboxes.setdefault(target, []).append((time.time(), body))
        self._reply(200, "{}")

    def do_GET(self):
        target = parse_path(self.path)
        if target is None:
            self._reply(400, "[]")
            return
        with lock:
            sweep(time.time())
            # Destructive read, but only of THIS recipient's mailbox. The sender can no
            # longer drain the message it just posted while polling for replies.
            messages = [body for _, body in mailboxes.pop(target, [])]
        self._reply(200, json.dumps(messages))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8789)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"ExtYomi relay listening on 0.0.0.0:{args.port}")
    server.serve_forever()
