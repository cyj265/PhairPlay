#!/usr/bin/env python3
"""
Audio cast verification — what does the TV music card actually show?

Two runs against the live box:

  1. `--meta`     : SetAVTransportURI carries DIDL-Lite with
                    dc:title="发送端标题", upnp:artist="发送端歌手" and an
                    albumArtURI served by this script. The card must show
                    those (sender metadata wins) and the cover on the disc.
  2. `--no-meta`  : the same file is cast with empty metadata. The card must
                    fall back to the ID3 tags embedded in the file
                    (title="标签标题", artist="标签歌手") and the embedded APIC
                    cover — not the file name.

The mp3 fixture is generated from probe/long_sample.mp3 (30 s, so the card
stays up long enough to screenshot) by replacing its ID3 tag, so the test does
not depend on whatever tags a download happens to carry.

Env: BOX_IP (192.168.10.10), BOX_PORT (8899), BOX_UDN (auto from /debug),
MY_IP (auto), MY_PORT (8895), ADB (path to adb).
"""

import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BOX_IP = os.environ.get("BOX_IP", "192.168.10.10")
BOX_PORT = os.environ.get("BOX_PORT", "8899")
BASE = f"http://{BOX_IP}:{BOX_PORT}"
AVT = "urn:schemas-upnp-org:service:AVTransport:1"
ADB = os.environ.get("ADB", "D:/AndroidSdk/platform-tools/adb.exe")
ADB_SERIAL = os.environ.get("ADB_SERIAL", f"{BOX_IP}:5555")

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, "shots")
SAMPLE = os.path.join(HERE, "long_sample.mp3")
TAGGED = os.path.join(HERE, "music_tagged.mp3")
COVER = os.path.join(HERE, "cover.png")

TAG_TITLE = "标签标题"
TAG_ARTIST = "标签歌手"
SENDER_TITLE = "发送端标题"
SENDER_ARTIST = "发送端歌手"


def detect_lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


MY_IP = os.environ.get("MY_IP") or detect_lan_ip()
MY_PORT = int(os.environ.get("MY_PORT", "8895"))


# ─────────────────────── ID3v2.3 fixture ───────────────────────

def _synchsafe(n):
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def build_cover_png(path, size=400):
    """A 400 px cover with a clear orientation cue.

    The disc rotates, so the picture needs an asymmetric pattern; a plain
    gradient would make a spinning record look like a still frame.
    """
    rows = []
    cx = cy = size / 2
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            r = int(30 + 200 * x / size)
            g = int(40 + 120 * y / size)
            b = int(140 + 90 * ((x + y) % size) / size)
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if abs(d - size * 0.28) < size * 0.05:
                r, g, b = 245, 245, 245
            elif d < size * 0.12:
                r, g, b = 250, 200, 40
            row += bytes((r, g, b))
        rows.append(bytes(row))

    raw = zlib.compress(b"".join(rows), 9)

    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", raw)
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    print(f"[cover] {path} ({len(png)} bytes)")


def _apic_frame(png_bytes):
    # encoding 0x00, mime + NUL, picture type 3 (front cover), empty desc
    payload = b"\x00image/png\x00\x03\x00" + png_bytes
    return b"APIC" + len(payload).to_bytes(4, "big") + b"\x00\x00" + payload


def _text_frame(frame_id, text):
    # ID3v2.3 text frame body: <encoding byte 0x01 = UTF-16 with BOM> <BOM> <text>
    payload = b"\x01\xff\xfe" + text.encode("utf-16-le")
    return frame_id.encode() + len(payload).to_bytes(4, "big") + b"\x00\x00" + payload


def build_tagged_mp3():
    with open(SAMPLE, "rb") as f:
        data = f.read()
    audio = data
    if data[:3] == b"ID3":
        size = ((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14) \
            | ((data[8] & 0x7F) << 7) | (data[9] & 0x7F)
        audio = data[10 + size:]
    frames = _text_frame("TIT2", TAG_TITLE) + _text_frame("TPE1", TAG_ARTIST) \
        + _text_frame("TALB", "标签专辑")
    with open(COVER, "rb") as f:
        frames += _apic_frame(f.read())
    tag = b"ID3\x03\x00\x00" + _synchsafe(len(frames)) + frames
    with open(TAGGED, "wb") as f:
        f.write(tag + audio)
    print(f"[fixture] {TAGGED} ({os.path.getsize(TAGGED)} bytes, "
          f"title={TAG_TITLE!r} artist={TAG_ARTIST!r})")


# ─────────────────────── helpers ───────────────────────

def get_udn():
    udn = os.environ.get("BOX_UDN")
    if udn:
        return udn
    text = urlopen(f"{BASE}/debug", timeout=6).read().decode("utf-8", "replace")
    m = re.search(r"/upnp/dev/([0-9a-fA-F-]{36})/desc", text)
    return m.group(1) if m else None


NOTIFIES = []


class Handler(BaseHTTPRequestHandler):
    def _empty(self, code=200):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        if self.path.startswith("/music.mp3"):
            with open(TAGGED, "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Accept-Ranges", "none")
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.startswith("/cover.png"):
            with open(COVER, "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self._empty(200)

    def do_NOTIFY(self):
        try:
            n = int(self.headers.get("Content-Length", "0") or "0")
            NOTIFIES.append(self.rfile.read(n) if n else b"")
        except Exception:
            pass
        self._empty(200)

    def log_message(self, *a):
        pass


def soap(action, inner):
    env = ('<?xml version="1.0" encoding="utf-8"?>'
           '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
           f"<s:Body>{inner}</s:Body></s:Envelope>")
    req = Request(f"{BASE}/upnp/dev/{UDN}/svc/upnp-org/AVTransport/action",
                  data=env.encode("utf-8"), method="POST")
    req.add_header("Content-Type", 'text/xml; charset="utf-8"')
    req.add_header("SOAPACTION", f'"{AVT}#{action}"')
    try:
        with urlopen(req, timeout=10) as r:
            return r.read().decode("utf-8", "replace")
    except HTTPError as e:
        return e.read().decode("utf-8", "replace")
    except URLError as e:
        return f"ERROR {e}"


def subscribe():
    req = Request(f"{BASE}/upnp/dev/{UDN}/svc/upnp-org/AVTransport/event", method="SUBSCRIBE")
    req.add_header("CALLBACK", f"<http://{MY_IP}:{MY_PORT}/event>")
    req.add_header("NT", "upnp:event")
    req.add_header("TIMEOUT", "Second-1800")
    with urlopen(req, timeout=8) as r:
        return r.headers.get("SID", "")


def didl(url, title=None, artist=None, art=None):
    doc = ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
           'xmlns:dc="http://purl.org/dc/elements/1.1/" '
           'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
           '<item id="1" parentID="0" restricted="1">')
    if title:
        doc += f"<dc:title>{title}</dc:title>"
    if artist:
        doc += f"<upnp:artist>{artist}</upnp:artist>"
    if art:
        doc += f"<upnp:albumArtURI>{art}</upnp:albumArtURI>"
    doc += f'<res protocolInfo="http-get:*:audio/mpeg:*">{url}</res></item></DIDL-Lite>'
    return doc.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def cast(url, title=None, artist=None, art=None):
    meta = didl(url, title, artist, art) if (title or artist or art) else ""
    soap("Stop", f'<u:Stop xmlns:u="{AVT}"><InstanceID>0</InstanceID></u:Stop>')
    time.sleep(1)
    resp = soap(
        "SetAVTransportURI",
        f'<u:SetAVTransportURI xmlns:u="{AVT}"><InstanceID>0</InstanceID>'
        f"<CurrentURI>{url}</CurrentURI>"
        f"<CurrentURIMetaData>{meta}</CurrentURIMetaData></u:SetAVTransportURI>",
    )
    time.sleep(1)
    soap("Play", f'<u:Play xmlns:u="{AVT}"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>')
    return resp


def position():
    resp = soap("GetPositionInfo",
                f'<u:GetPositionInfo xmlns:u="{AVT}"><InstanceID>0</InstanceID>'
                "</u:GetPositionInfo>")
    rt = re.search(r"<RelTime>([^<]*)</RelTime>", resp)
    dur = re.search(r"<TrackDuration>([^<]*)</TrackDuration>", resp)
    return (rt.group(1) if rt else "-", dur.group(1) if dur else "-")


def screenshot(name):
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, name)
    with open(path, "wb") as f:
        subprocess.run([ADB, "-s", ADB_SERIAL, "exec-out", "screencap", "-p"],
                       stdout=f, timeout=30, check=False)
    print(f"[shot] {path}")
    return path


def run_case(label, title, artist, art, shot_name):
    print(f"\n== {label} ==")
    url = f"http://{MY_IP}:{MY_PORT}/music.mp3?run={int(time.time())}"
    resp = cast(url, title, artist, art)
    print(f"  SetAVTransportURI: {'OK' if 'SetAVTransportURIResponse' in resp else resp[:80]}")
    samples = []
    for _ in range(6):
        time.sleep(1)
        samples.append(position())
    print(f"  RelTime samples: {[s[0] for s in samples]} duration={samples[-1][1]}")
    playing = any(s[0] not in ("-", "00:00:00") for s in samples)
    print(f"  audio is playing: {playing}")
    screenshot(shot_name)
    log = urlopen(f"{BASE}/debug", timeout=8).read().decode("utf-8", "replace")
    for line in log.splitlines():
        if "开始播放" in line or "audioOnly" in line or "内嵌" in line:
            print(f"  log: {line.strip()}")
    soap("Stop", f'<u:Stop xmlns:u="{AVT}"><InstanceID>0</InstanceID></u:Stop>')
    time.sleep(1)
    return playing


def main():
    global UDN
    build_cover_png(COVER)
    build_tagged_mp3()
    UDN = get_udn()
    if not UDN:
        print("cannot resolve UDN from /debug")
        return 2
    server = ThreadingHTTPServer((MY_IP, MY_PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    time.sleep(0.3)
    sid = subscribe()
    print(f"box={BOX_IP} udn={UDN} my_ip={MY_IP} sid={sid}")

    art = f"http://{MY_IP}:{MY_PORT}/cover.png?run={int(time.time())}"
    didl_ok = run_case("DIDL metadata cast (sender title/artist/art)", SENDER_TITLE, SENDER_ARTIST,
                       art, "music-with-didl.png")
    tags_ok = run_case("no metadata (embedded ID3 + APIC fallback)", None, None, None,
                       "music-with-tags.png")

    if sid:
        req = Request(f"{BASE}/upnp/dev/{UDN}/svc/upnp-org/AVTransport/event",
                      method="UNSUBSCRIBE")
        req.add_header("SID", sid)
        try:
            urlopen(req, timeout=8).read()
        except Exception:
            pass
    server.shutdown()
    print(f"\nRESULT: didl={'PASS' if didl_ok else 'FAIL'} tags={'PASS' if tags_ok else 'FAIL'}")
    return 0 if (didl_ok and tags_ok) else 1


if __name__ == "__main__":
    sys.exit(main())
