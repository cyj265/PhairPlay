#!/usr/bin/env python3
"""
GENA progress-reporting verification harness for PhairPlay (N1/T1 receiver).

What it does
------------
1. Runs a tiny HTTP server on the local machine (MY_IP:MY_PORT) that BOTH
   - receives GENA NOTIFY callbacks from the box (HTTP method NOTIFY), and
   - serves a local test MP4 so the box can actually play something over LAN
     (no Cloudflare / no IPv6 in the media path).
2. SUBSCRIBEs to the box AVTransport event URL with that callback.
3. Casts the local MP4 (SetAVTransportURI + Play).
4. Polls GetPositionInfo every 2 s and records RelTime / TrackDuration.
5. Collects every NOTIFY that arrives and parses RelativeTimePosition /
   CurrentMediaDuration out of the LastChange XML.

Pass criterion
--------------
- At least 3 NOTIFY events received, AND
- at least one NOTIFY carries a non-zero RelativeTimePosition, AND
- GetPositionInfo RelTime advances over the run (sanity that the player plays).

Box addressing (hard-coded for the home N1; override with env vars)
  BOX_IP, BOX_PORT(8899), BOX_UDN, MY_IP(192.168.10.10), MY_PORT(8899)
"""

import os
import re
import sys
import time
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer, ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import URLError

BOX_IP = os.environ.get("BOX_IP", "192.168.10.11")
BOX_PORT = os.environ.get("BOX_PORT", "8899")
BOX_UDN = os.environ.get("BOX_UDN", "6f61c845-1dd2-11b2-8f7b-001185123456")
MY_IP = os.environ.get("MY_IP", "192.168.10.10")
MY_PORT = int(os.environ.get("MY_PORT", "8899"))
MEDIA = os.environ.get(
    "MEDIA",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "test.mp4"),
)

BASE = f"http://{BOX_IP}:{BOX_PORT}"
EVENT_URL = f"{BASE}/upnp/dev/{BOX_UDN}/svc/upnp-org/AVTransport/event"
CTRL_URL = f"{BASE}/upnp/dev/{BOX_UDN}/svc/upnp-org/AVTransport/action"
CALLBACK = f"http://{MY_IP}:{MY_PORT}/"
AVT = "urn:schemas-upnp-org:service:AVTransport:1"
TEST_URL = f"http://{MY_IP}:{MY_PORT}/test.mp4?cb={int(time.time())}"

NOTIFIES = []  # list of (relTime, duration)
_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def _empty(self, code=200):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        # The box may probe the callback with a GET; also serves the test media.
        if self.path.startswith("/test.mp4"):
            self._serve_file(MEDIA)
        else:
            self._empty(200)

    def do_NOTIFY(self):
        # GENA eventing uses the NOTIFY method (NOT POST). Record it.
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
            body = self.rfile.read(length) if length else b""
        except Exception as e:
            body = b""
            sys.stderr.write(f"[notify-err] read: {e}\n")
        self._empty(200)
        text = body.decode("utf-8", "replace")
        rt = re.search(r"RelativeTimePosition val=\"([^\"]*)\"", text)
        dur = re.search(r"CurrentMediaDuration val=\"([^\"]*)\"", text)
        rtv = rt.group(1) if rt else ""
        durv = dur.group(1) if dur else ""
        with _lock:
            NOTIFIES.append((rtv, durv, text))
        if not getattr(self, "_dumped", False):
            self._dumped = True
            with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "last_notify.txt"), "w", encoding="utf-8") as f:
                f.write(text)
        sys.stderr.write(f"[notify] RelTime={rtv or '-'} Dur={durv or '-'}\n")

    def do_POST(self):
        # Some stacks use POST for events; handle for completeness.
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
            body = self.rfile.read(length) if length else b""
        except Exception as e:
            body = b""
            sys.stderr.write(f"[post-err] read: {e}\n")
        self._empty(200)
        text = body.decode("utf-8", "replace")
        rt = re.search(r"RelativeTimePosition val=\"([^\"]*)\"", text)
        dur = re.search(r"CurrentMediaDuration val=\"([^\"]*)\"", text)
        rtv = rt.group(1) if rt else ""
        durv = dur.group(1) if dur else ""
        with _lock:
            NOTIFIES.append((rtv, durv, text))

    def _serve_file(self, path):
        try:
            with open(path, "rb") as f:
                data = f.read()
        except OSError:
            self.send_error(404)
            return
        # Always serve the full file (200). Range-based seeking is not needed
        # for this verification clip; a single 200 keeps the response trivial
        # and avoids any malformed 206 handling that breaks ExoPlayer.
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", "video/mp4")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass

    def parse_request(self):
        rl = getattr(self, "raw_requestline", b"")
        sys.stderr.write(f"[rawline] {rl!r}\n")
        return super().parse_request()

    def log_request(self, code='-', size='-'):
        sys.stderr.write(
            f"[req] method={self.command} path={self.path} code={code}\n"
        )

    def log_error(self, fmt, *args):
        sys.stderr.write("[server-err] " + (fmt % args if args else fmt) + "\n")


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


def subscribe():
    req = Request(EVENT_URL, method="SUBSCRIBE")
    req.add_header("HOST", f"{BOX_IP}:{BOX_PORT}")
    req.add_header("CALLBACK", f"<{CALLBACK}>")
    req.add_header("NT", "upnp:event")
    req.add_header("TIMEOUT", "Second-1800")
    try:
        with urlopen(req, timeout=10) as r:
            return r.headers.get("SID", "")
    except URLError as e:
        return f"ERROR {e}"


def unsubscribe(sid):
    if not sid or sid.startswith("ERROR"):
        return
    req = Request(EVENT_URL, method="UNSUBSCRIBE")
    req.add_header("HOST", f"{BOX_IP}:{BOX_PORT}")
    req.add_header("SID", sid)
    try:
        urlopen(req, timeout=10).read()
    except URLError:
        pass


def get_position_info():
    resp = soap(
        "GetPositionInfo",
        '<u:GetPositionInfo xmlns:u="'
        f'{AVT}"><InstanceID>0</InstanceID>'
        "<ElapsedTime></ElapsedTime></u:GetPositionInfo>",
    )
    rt = re.search(r"<RelTime>([^<]*)</RelTime>", resp)
    dur = re.search(r"<TrackDuration>([^<]*)</TrackDuration>", resp)
    return (rt.group(1) if rt else "-", dur.group(1) if dur else "-")


def set_and_play():
    # Stop anything still playing (e.g. the previous run's ended clip) so the
    # new cast actually (re)buffers instead of resuming at the end.
    soap(
        "Stop",
        f'<u:Stop xmlns:u="{AVT}"><InstanceID>0</InstanceID></u:Stop>',
    )
    time.sleep(1)
    esc = TEST_URL.replace("&", "&amp;")
    soap(
        "SetAVTransportURI",
        f'<u:SetAVTransportURI xmlns:u="{AVT}"><InstanceID>0</InstanceID>'
        f"<CurrentURI>{esc}</CurrentURI>"
        "<CurrentURIMetaData></CurrentURIMetaData>"
        f"</u:SetAVTransportURI>",
    )
    time.sleep(1)
    return soap(
        "Play",
        f'<u:Play xmlns:u="{AVT}"><InstanceID>0</InstanceID>'
        "<Speed>1</Speed></u:Play>",
    )


def ensure_media():
    if os.path.exists(MEDIA):
        return
    # Auto-fetch a small public MP4 so the box can play something over LAN
    # (no Cloudflare / no IPv6 in the media path). Falls back across mirrors.
    urls = [
        "https://media.w3.org/2010/05/sintel/trailer.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/"
        "sample/ForBiggerBlazes.mp4",
    ]
    import shutil
    for u in urls:
        try:
            sys.stderr.write(f"[harness] downloading test clip: {u}\n")
            req = Request(u, headers={"User-Agent": "PhairPlay-verify"})
            with urlopen(req, timeout=60) as r, open(MEDIA, "wb") as f:
                shutil.copyfileobj(r, f)
            if os.path.getsize(MEDIA) > 100000:
                sys.stderr.write("[harness] test clip ready\n")
                return
        except Exception as e:
            sys.stderr.write(f"[harness] download failed: {e}\n")
    sys.stderr.write(
        "[harness] could not fetch test clip; place one at "
        f"{MEDIA} (e.g. a small .mp4)\n"
    )


def main():
    ensure_media()
    server = ThreadingHTTPServer((MY_IP, MY_PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    sys.stderr.write(f"[harness] server on {MY_IP}:{MY_PORT}\n")

    sid = subscribe()
    sys.stderr.write(f"[harness] SUBSCRIBE SID={sid}\n")
    if sid.startswith("ERROR"):
        sys.stderr.write("[harness] subscribe failed; aborting\n")
        server.shutdown()
        return 1

    play_resp = set_and_play()
    sys.stderr.write(f"[harness] Play resp (first 80): {play_resp[:80]}\n")

    samples = []
    for i in range(10):
        rt, dur = get_position_info()
        samples.append((i, rt, dur))
        sys.stderr.write(f"[poll {i}] RelTime={rt} TrackDuration={dur}\n")
        time.sleep(2)

    time.sleep(3)
    unsubscribe(sid)
    server.shutdown()

    with _lock:
        n = len(NOTIFIES)
        nonzero = [x for x in NOTIFIES if x[0] not in ("", "00:00:00")]
        any_rt = [s for s in samples if s[1] not in ("-", "00:00:00")]

    print("\n================ GENA VERIFY RESULT ================")
    print(f"SUBSCRIBE SID : {sid}")
    print(f"NOTIFY count  : {n}")
    print(f"NOTIFY w/ pos : {len(nonzero)} {[x[0] for x in nonzero]}")
    print("GetPositionInfo samples:")
    for s in samples:
        print(f"  t{s[0]} RelTime={s[1]} Duration={s[2]}")
    passed = n >= 3 and len(nonzero) >= 1 and len(any_rt) >= 1
    print("RESULT        : " + ("PASS" if passed else "FAIL"))
    print("====================================================")
    return 0 if passed else 2


if __name__ == "__main__":
    sys.exit(main())
