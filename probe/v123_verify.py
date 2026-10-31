#!/usr/bin/env python3
"""
v1.0.123 port verification — GENA policy, media-URI policy, DIAL, photo cap.

Runs against a live N1 box (default 192.168.10.10:8899) and checks the four
blocks ported from upstream mazer666/PhairPlay:

  1. GENA   : SUBSCRIBE with a private same-subnet callback -> 200 + initial
              NOTIFY; loopback / link-local / public / DNS-name callbacks -> 412;
              a short TIMEOUT subscription disappears after expiry.
  2. URI    : SetAVTransportURI refuses (SOAP fault) link-local, loopback,
              octal notation, over-long and control-character URIs; a normal
              LAN http URI is still accepted.
  3. DIAL   : GET /dial/ (list), /dial/PhairPlay (info), unknown -> 404; the
              device description carries Application-Url + serialNumber +
              modelURL + presentationURL.
  4. PHOTO  : a >20 MB image is refused by the downloader (see /debug log).
  5. SSDP   : M-SEARCH with the Samsung MultiScreen ST is answered, and the
              ST is echoed.

Env: BOX_IP (default 192.168.10.10), BOX_PORT (8899), MY_IP (auto), BOX_UDN
(auto-read from /debug), SKIP_EXPIRY=1 to skip the ~65 s expiry wait.
"""

import os
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BOX_IP = os.environ.get("BOX_IP", "192.168.10.10")
BOX_PORT = os.environ.get("BOX_PORT", "8899")
BASE = f"http://{BOX_IP}:{BOX_PORT}"
AVT = "urn:schemas-upnp-org:service:AVTransport:1"
SAMSUNG_ST = "urn:samsung.com:service:MultiScreenService:1"
PHOTO_BYTES = 21 * 1024 * 1024

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))


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


def get_udn():
    udn = os.environ.get("BOX_UDN")
    if udn:
        return udn
    try:
        text = urlopen(f"{BASE}/debug", timeout=6).read().decode("utf-8", "replace")
    except Exception as e:
        print(f"cannot read /debug: {e}")
        return None
    m = re.search(r"/upnp/dev/([0-9a-fA-F-]{36})/desc", text)
    return m.group(1) if m else None


# ─────────────────────────── GENA test server ───────────────────────────

NOTIFIES = []
_notify_lock = threading.Lock()


class GenaHandler(BaseHTTPRequestHandler):
    def _empty(self, code=200):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_NOTIFY(self):
        try:
            n = int(self.headers.get("Content-Length", "0") or "0")
            body = self.rfile.read(n) if n else b""
        except Exception:
            body = b""
        self._empty(200)
        with _notify_lock:
            NOTIFIES.append(body.decode("utf-8", "replace"))
        print(f"  [notify] {len(body)} bytes")

    def do_GET(self):
        self._empty(200)

    def log_message(self, *a):
        pass


class PhotoHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/photo.jpg"):
            with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "photo.jpg"), "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if self.path.startswith("/big.jpg"):
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(PHOTO_BYTES))
            self.end_headers()
            try:
                self.wfile.write(b"\xff\xd8\xff\xe0")
                chunk = b"\x00" * (64 * 1024)
                sent = 4
                while sent < PHOTO_BYTES:
                    n = min(len(chunk), PHOTO_BYTES - sent)
                    self.wfile.write(chunk[:n])
                    sent += n
            except Exception:
                pass
            return
        self.send_error(404)

    def log_message(self, *a):
        pass


# ─────────────────────────── helpers ───────────────────────────

def subscribe(callback, timeout="Second-1800"):
    req = Request(f"{BASE}/upnp/dev/{UDN}/svc/upnp-org/AVTransport/event", method="SUBSCRIBE")
    req.add_header("CALLBACK", callback)
    req.add_header("NT", "upnp:event")
    req.add_header("TIMEOUT", timeout)
    try:
        with urlopen(req, timeout=8) as r:
            return r.status, r.headers.get("SID", ""), r.headers.get("TIMEOUT", "")
    except HTTPError as e:
        return e.code, "", ""
    except URLError as e:
        return -1, str(e), ""


def unsubscribe(sid):
    if not sid:
        return
    req = Request(f"{BASE}/upnp/dev/{UDN}/svc/upnp-org/AVTransport/event", method="UNSUBSCRIBE")
    req.add_header("SID", sid)
    try:
        urlopen(req, timeout=8).read()
    except Exception:
        pass


def soap(action, inner):
    env = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
        f"<s:Body>{inner}</s:Body></s:Envelope>"
    )
    req = Request(
        f"{BASE}/upnp/dev/{UDN}/svc/upnp-org/AVTransport/action",
        data=env.encode("utf-8"),
        method="POST",
    )
    req.add_header("Content-Type", 'text/xml; charset="utf-8"')
    req.add_header("SOAPACTION", f'"{AVT}#{action}"')
    try:
        with urlopen(req, timeout=10) as r:
            return r.read().decode("utf-8", "replace")
    except HTTPError as e:
        return e.read().decode("utf-8", "replace")
    except URLError as e:
        return f"ERROR {e}"


def set_uri(uri):
    escaped = uri.replace("&", "&amp;").replace("<", "&lt;")
    return soap(
        "SetAVTransportURI",
        f'<u:SetAVTransportURI xmlns:u="{AVT}"><InstanceID>0</InstanceID>'
        f"<CurrentURI>{escaped}</CurrentURI>"
        "<CurrentURIMetaData></CurrentURIMetaData>"
        "</u:SetAVTransportURI>",
    )


def play():
    return soap(
        "Play",
        f'<u:Play xmlns:u="{AVT}"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>',
    )


def is_fault(body):
    return "<s:Fault>" in body or "<Fault>" in body


def msearch(st, wait=3.0):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((MY_IP, 0))
    try:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(MY_IP))
    except OSError:
        pass
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    msg = (
        "M-SEARCH * HTTP/1.1\r\n"
        "HOST: 239.255.255.250:1900\r\n"
        'MAN: "ssdp:discover"\r\n'
        "MX: 2\r\n"
        f"ST: {st}\r\n\r\n"
    ).encode()
    sock.sendto(msg, ("239.255.255.250", 1900))
    sock.settimeout(0.4)
    out = []
    deadline = time.time() + wait
    while time.time() < deadline:
        try:
            data, addr = sock.recvfrom(4096)
        except socket.timeout:
            continue
        except OSError:
            break
        text = data.decode("utf-8", "replace")
        out.append(text)
    sock.close()
    return out


def header_of(text, name):
    for line in text.splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            if k.strip().lower() == name.lower():
                return v.strip()
    return ""


# ─────────────────────────── tests ───────────────────────────

def test_descriptor():
    print("\n== device description ==")
    req = Request(f"{BASE}/upnp/dev/{UDN}/desc")
    with urlopen(req, timeout=8) as r:
        body = r.read().decode("utf-8", "replace")
        app_url = r.headers.get("Application-Url", "")
    check("Application-Url header", app_url == f"{BASE}/dial", app_url)
    for field in ("serialNumber", "modelURL", "presentationURL"):
        m = re.search(rf"<{field}>([^<]+)</{field}>", body)
        check(f"descriptor has {field}", bool(m), m.group(1) if m else "missing")


def test_dial():
    print("\n== DIAL ==")
    body = urlopen(f"{BASE}/dial/", timeout=8).read().decode("utf-8", "replace")
    check("/dial/ app list", '<app name="PhairPlay" id="PhairPlay"' in body)
    body = urlopen(f"{BASE}/dial/PhairPlay", timeout=8).read().decode("utf-8", "replace")
    check("/dial/PhairPlay info", "<state>stopped</state>" in body and "<name>PhairPlay</name>" in body)
    try:
        code = urlopen(f"{BASE}/dial/Nope", timeout=8).status
    except HTTPError as e:
        code = e.code
    check("/dial/Nope -> 404", code == 404, str(code))


def test_ssdp():
    print("\n== SSDP Samsung ==")
    replies = msearch(SAMSUNG_ST)
    samsung = [r for r in replies if header_of(r, "ST") == SAMSUNG_ST]
    check("Samsung M-SEARCH answered", bool(samsung),
          f"{len(samsung)}/{len(replies)} replies")
    if samsung:
        usn = header_of(samsung[0], "USN")
        check("Samsung USN pairs our UDN", usn.startswith(f"uuid:{UDN}::"), usn)
    replies = msearch("ssdp:all", wait=4.0)
    check("ssdp:all answered with several resources", len(replies) >= 6, f"{len(replies)} datagrams")


def test_gena():
    print("\n== GENA ==")
    server = ThreadingHTTPServer((MY_IP, 8897), GenaHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    good = f"http://{MY_IP}:8897/event"
    time.sleep(0.3)
    code, sid, timeout = subscribe(f"<{good}>", "Second-1")
    check("allowed callback accepted (200 + SID)", code == 200 and sid.startswith("uuid:"),
          f"{code} sid={sid} timeout={timeout}")
    deadline = time.time() + 4
    while time.time() < deadline and not NOTIFIES:
        time.sleep(0.2)
    check("initial NOTIFY delivered to callback", bool(NOTIFIES), f"{len(NOTIFIES)} notify")

    bad = [
        ("loopback", "http://127.0.0.1:8897/e"),
        ("link-local metadata", "http://169.254.169.254/latest"),
        ("public address", "http://8.8.8.8/e"),
        ("DNS name", "http://evil.example.com/e"),
        ("user info", "http://a@192.168.10.12/e"),
        ("non-http scheme", "ftp://192.168.10.12/e"),
    ]
    for label, url in bad:
        code, _, _ = subscribe(f"<{url}>")
        check(f"refused {label}", code == 412, str(code))

    # Expiry: the allowed subscription used Second-1, clamped to 30 s. Renewal
    # after expiry must NOT return the same SID (a fresh subscription is fine).
    if not os.environ.get("SKIP_EXPIRY"):
        print("  waiting ~65 s for subscription expiry …")
        time.sleep(65)
        req = Request(f"{BASE}/upnp/dev/{UDN}/svc/upnp-org/AVTransport/event", method="SUBSCRIBE")
        req.add_header("SID", sid)
        req.add_header("TIMEOUT", "Second-1800")
        try:
            with urlopen(req, timeout=8) as r:
                renewed_sid = r.headers.get("SID", "")
                renewed_code = r.status
        except HTTPError as e:
            renewed_code = e.code
            renewed_sid = ""
        check("expired subscription is gone (renewal gets a new SID)",
              not (renewed_code == 200 and renewed_sid == sid),
              f"{renewed_code} sid={renewed_sid}")
        unsubscribe(renewed_sid)
    else:
        unsubscribe(sid)
    server.shutdown()


def test_uri_policy():
    print("\n== media URI policy ==")
    cases = [
        ("link-local", "http://169.254.169.254/latest"),
        ("loopback", "http://127.0.0.1/x.mp4"),
        ("octal notation", "http://0177.0.0.1/x.mp4"),
        ("multicast", "http://224.0.0.1/x.mp4"),
        ("over-long", "http://192.168.10.12/" + "a" * 5000 + ".mp4"),
        ("control char", "http://192.168.10.12/a\x01b.mp4"),
        ("bad scheme", "javascript:alert(1)"),
    ]
    for label, uri in cases:
        body = set_uri(uri)
        check(f"refused {label}", is_fault(body),
              "fault" if is_fault(body) else body[:60].replace("\n", " "))


def test_photo_cap():
    print("\n== photo 20 MB cap ==")
    server = ThreadingHTTPServer((MY_IP, 8898), PhotoHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    time.sleep(0.3)
    body = set_uri(f"http://{MY_IP}:8898/big.jpg")
    accepted = "SetAVTransportURIResponse" in body
    check("normal LAN http URI accepted", accepted, body[:60].replace("\n", " "))
    play()
    time.sleep(6)
    log = urlopen(f"{BASE}/debug", timeout=8).read().decode("utf-8", "replace")
    check("21 MB photo refused by the 20 MB cap", "超过 20MB 上限" in log)
    # A normal photo must still go through — the cap is a ceiling, not a filter.
    body = set_uri(f"http://{MY_IP}:8898/photo.jpg")
    check("normal-size photo URI accepted", "SetAVTransportURIResponse" in body)
    play()
    time.sleep(6)
    log = urlopen(f"{BASE}/debug", timeout=8).read().decode("utf-8", "replace")
    check("normal photo downloaded and shown", "照片已下载" in log)
    soap("Stop", f'<u:Stop xmlns:u="{AVT}"><InstanceID>0</InstanceID></u:Stop>')
    server.shutdown()


def main():
    global UDN
    UDN = get_udn()
    if not UDN:
        print("FAIL: cannot resolve the box UDN from /debug")
        return 2
    print(f"box={BOX_IP}:{BOX_PORT} udn={UDN} my_ip={MY_IP}")
    test_descriptor()
    test_dial()
    test_ssdp()
    test_gena()
    test_uri_policy()
    test_photo_cap()

    failed = [r for r in RESULTS if not r[1]]
    print("\n================ v1.0.123 VERIFY ================")
    for name, ok, detail in RESULTS:
        print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail else ""))
    print(f"RESULT: {'PASS' if not failed else 'FAIL'} ({len(RESULTS) - len(failed)}/{len(RESULTS)})")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
