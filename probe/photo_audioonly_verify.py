"""Real-device check for v1.0.120 (two fixes reported by the diagnostic side).

Fix 1 (photo vs. audio-only fallback):
    Rapid-cast several distinct URIs so the 60 s foreground budget is
    exhausted, which puts the next cast on the "audio only" path, then cast a
    JPEG. Before v1.0.120 the image test was gated on `!audioOnly`, so the
    JPEG reached ExoPlayer and produced
    ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED. After the fix the box must log
    the photo-screen branch instead, with no parse error.

Fix 2 (Ipv4DS short-read log flood):
    Count `Ipv4DS read@` lines in the box diagnostic log. They used to run to
    500+ per item; the throttled source emits at most one per 5 s window.

Markers are read from the box itself (8099), never from this script's
expectations.

Env: BOX_IP (default 192.168.10.11), BOX_PORT(8899), BOX_UDN, MY_IP(auto),
MY_PORT(8899), MEDIA(probe/test.mp4), PHOTO(any local .jpg).
"""

import os
import re
import socket
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import URLError
from urllib.request import Request, urlopen

BOX_IP = os.environ.get("BOX_IP", "192.168.10.11")
BOX_PORT = int(os.environ.get("BOX_PORT", "8899"))
BOX_UDN = os.environ.get("BOX_UDN", "6f61c845-1dd2-11b2-8f7b-001185123456")

HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.environ.get("MEDIA", os.path.join(HERE, "test.mp4"))
PHOTO = os.environ.get(
    "PHOTO",
    os.path.join(HERE, "photo.jpg"),
)


def _detect_lan_ip() -> str:
    """Source address used for outbound traffic (DHCP here, so never hard-code)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


MY_IP = os.environ.get("MY_IP") or _detect_lan_ip()
MY_PORT = int(os.environ.get("MY_PORT", "8899"))

BASE = f"http://{BOX_IP}:{BOX_PORT}"
CTRL_URL = f"{BASE}/upnp/dev/{BOX_UDN}/svc/upnp-org/AVTransport/action"
AVT = "urn:schemas-upnp-org:service:AVTransport:1"

_lock = threading.Lock()
HITS = []


class Handler(BaseHTTPRequestHandler):
    def _empty(self, code=200):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/test.mp4":
            self._serve(MEDIA, "video/mp4")
        elif path == "/photo.jpg":
            self._serve(PHOTO, "image/jpeg")
        else:
            self._empty(200)

    def _serve(self, path, ctype):
        try:
            with open(path, "rb") as f:
                data = f.read()
        except OSError:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass

    def log_request(self, code="-", size="-"):
        with _lock:
            HITS.append((self.command, self.path, code))


def soap(action, body_inner):
    env = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        f"<s:Body>{body_inner}</s:Body></s:Envelope>"
    )
    req = Request(CTRL_URL, data=env.encode("utf-8"), method="POST")
    req.add_header("Content-Type", 'text/xml; charset="utf-8"')
    req.add_header("SOAPACTION", f'"{AVT}#{action}"')
    try:
        with urlopen(req, timeout=10) as r:
            return r.read().decode("utf-8", "replace")
    except URLError as e:
        return f"ERROR {e}"


def cast(url):
    esc = url.replace("&", "&amp;")
    soap(
        "SetAVTransportURI",
        f'<u:SetAVTransportURI xmlns:u="{AVT}"><InstanceID>0</InstanceID>'
        f"<CurrentURI>{esc}</CurrentURI>"
        "<CurrentURIMetaData></CurrentURIMetaData>"
        "</u:SetAVTransportURI>",
    )
    time.sleep(0.8)
    return soap(
        "Play",
        f'<u:Play xmlns:u="{AVT}"><InstanceID>0</InstanceID>'
        "<Speed>1</Speed></u:Play>",
    )


def stop():
    return soap(
        "Stop",
        f'<u:Stop xmlns:u="{AVT}"><InstanceID>0</InstanceID></u:Stop>',
    )


def box_log():
    try:
        with urlopen(f"http://{BOX_IP}:8099/log", timeout=10) as r:
            return r.read().decode("utf-8", "replace")
    except Exception as e:
        return f"ERROR {e}"


def clear_log():
    try:
        urlopen(f"http://{BOX_IP}:8099/clear", timeout=10).read()
    except Exception:
        pass


def count(text, needle):
    return len(re.findall(re.escape(needle), text))


def main():
    if not os.path.exists(PHOTO):
        print(f"FAIL: no photo file at {PHOTO} (set PHOTO env var)")
        return 1
    if not os.path.exists(MEDIA):
        print(f"FAIL: no media file at {MEDIA}")
        return 1

    print(f"MY_IP={MY_IP} MY_PORT={MY_PORT} BOX={BOX_IP}")
    print(f"media={MEDIA} photo={PHOTO}")

    server = ThreadingHTTPServer((MY_IP, MY_PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    clear_log()
    time.sleep(1)

    # Phase 1: exhaust the 60 s foreground budget with distinct URIs so the
    # next cast lands on the audio-only fallback path.
    for n in range(1, 6):
        cast(f"http://{MY_IP}:{MY_PORT}/test.mp4?n={n}")
        print(f"[cast] test.mp4?n={n}")
        time.sleep(1.5)

    # Phase 2: the item the fix is about — a still image on that path.
    stop()
    time.sleep(1)
    cast(f"http://{MY_IP}:{MY_PORT}/photo.jpg")
    print("[cast] photo.jpg")

    time.sleep(25)

    text = box_log()
    audio_only = count(text, "仅音频起播")
    photo_screen = count(text, "识别为静态图片")
    photo_download = count(text, "照片已下载")
    parse_err = count(text, "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED")
    read_lines = len(re.findall(r"Ipv4DS read@", text))

    print()
    print("===== box log markers =====")
    print(f"仅音频起播                                 = {audio_only}")
    print(f"识别为静态图片 → 图片屏                    = {photo_screen}")
    print(f"照片已下载                                 = {photo_download}")
    print(f"ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED  = {parse_err}")
    print(f"Ipv4DS read@ 行数                          = {read_lines}")

    with _lock:
        jpg_hits = [h for h in HITS if "photo.jpg" in h[1]]
        mp4_hits = [h for h in HITS if "test.mp4" in h[1]]
    print(f"本机收到 /photo.jpg 请求 = {len(jpg_hits)}  /test.mp4 请求 = {len(mp4_hits)}")

    ok = True
    if photo_screen == 0:
        print("FAIL: 照片没有走图片屏分支")
        ok = False
    if parse_err > 0:
        print("FAIL: JPEG 仍被喂进播放器（解析容器失败）")
        ok = False
    if read_lines > 60:
        print(f"WARN: read@ 行数 {read_lines} 仍偏高（限流窗口 5s）")
    else:
        print(f"OK: read@ 行数 {read_lines}（无限流前为 500+）")

    server.shutdown()
    print()
    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
