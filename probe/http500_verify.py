#!/usr/bin/env python3
"""v1.0.121 verification - a sender that answers HTTP 500 with a short body.

Field case (2026-10-12 22:04): a cast from lanxing-vod arrived fine over SOAP
(SetAVTransportURI / Play / Seek all OK) but the media URL answered

    HTTP 500, Content-Type=text/plain, Content-Length=27, body "Path out..."

Two things in the box hid the cause:

1. the source probe read the body with Okio's readByteArray(200), which throws
   EOFException on any body shorter than 200 bytes - i.e. on every error page.
   The log said "(读取响应体失败: null)" and the only remaining clue was the
   8-byte hex the data source happened to dump.
2. a non-2xx answer updated no probe verdict, so the failure was filed purely
   as ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED - which reads like a gap in this
   box's format support instead of a refusal on the sending phone.

This harness stands in for the phone: a local server that answers 500 with a
27-byte text/plain body, cast to the box. Pass criteria:

  A. 源前200字节 shows the real body, not "(读取响应体失败"
  B. Ipv4DS line carries text="..." with the printable body
  C. 源探测: 发送端返回 HTTP 500 (non-2xx branch) is logged
  D. toast 显示 appears for BOTH casts - the hint is an event flow (v121),
     not a StateFlow that deduplicates the same text after the first cast

Usage: python probe/http500_verify.py
"""

import os
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen

BOX = os.environ.get("BOX_IP", "192.168.10.11")
UPNP_PORT = 8899
LOCAL_PORT = 8899
UDN = "6f61c845-1dd2-11b2-8f7b-001185123456"
AVT = "urn:schemas-upnp-org:service:AVTransport:1"
CTRL = f"http://{BOX}:{UPNP_PORT}/upnp/dev/{UDN}/svc/upnp-org/AVTransport/action"

# Exactly the shape of the field failure: short, plain text, HTTP 500.
BODY = b"Path outside root directory"[:27]


def my_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect(("8.8.8.8", 80))
    return s.getsockname()[0]


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(500)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)

    def do_HEAD(self):
        self.send_response(500)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()

    def log_message(self, *a):
        pass


def soap(action, body):
    env = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        "<s:Body>" + body + "</s:Body></s:Envelope>"
    )
    req = Request(CTRL, data=env.encode(), method="POST")
    req.add_header("Content-Type", 'text/xml; charset="utf-8"')
    req.add_header("SOAPACTION", f'"{AVT}#{action}"')
    try:
        return urlopen(req, timeout=10).read().decode("utf-8", "replace")
    except Exception as e:
        return f"ERR {e}"


def fetch_log():
    return urlopen(f"http://{BOX}:8099/log", timeout=15).read().decode("utf-8", "replace")


def clear_log():
    try:
        urlopen(f"http://{BOX}:8099/clear", timeout=10).read()
    except Exception:
        pass


def main():
    me = my_ip()
    print(f"box={BOX}  me={me}  fake sender on :{LOCAL_PORT} -> 500 + {len(BODY)}B text/plain")
    print(f"body={BODY!r}")
    srv = ThreadingHTTPServer((me, LOCAL_PORT), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    time.sleep(0.5)

    # Clean slate: a previous item still parked on the box would be swallowed by
    # the idempotent start guard and this cast would never reach the source.
    soap("Stop", f'<u:Stop xmlns:u="{AVT}"><InstanceID>0</InstanceID></u:Stop>')
    time.sleep(1)
    clear_log()
    time.sleep(1)

    # v121: cast the SAME broken uri twice. The first failure must toast, and
    # the second must toast AGAIN - as a StateFlow the identical hint text was
    # deduplicated and the user only ever saw one explanation.
    for i in range(2):
        url = f"http://{me}:{LOCAL_PORT}/bad.mp4?cb={int(time.time())}-{i}"
        soap("Stop", f'<u:Stop xmlns:u="{AVT}"><InstanceID>0</InstanceID></u:Stop>')
        time.sleep(1)
        soap(
            "SetAVTransportURI",
            f'<u:SetAVTransportURI xmlns:u="{AVT}"><InstanceID>0</InstanceID>'
            f"<CurrentURI>{url}</CurrentURI>"
            f"<CurrentURIMetaData></CurrentURIMetaData></u:SetAVTransportURI>",
        )
        time.sleep(1)
        soap("Play", f'<u:Play xmlns:u="{AVT}"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>')
        print(f"cast #{i + 1}: {url}")
        time.sleep(9)
    srv.shutdown()

    log = fetch_log()

    print("\n--- relevant log lines ---")
    for line in log.splitlines():
        if any(
            k in line
            for k in ("源前200字节", "源探测: 发送端", "Ipv4DS read@0", "v121", "toast 显示")
        ):
            print(line.rstrip()[:230])

    print("\n--- verdict ---")
    a = bool(re.search(r"源前200字节: (?!\(读取响应体失败)(?!\(空响应体)", log))
    b = bool(re.search(r'Ipv4DS read@0.* text="[^"]+"', log))
    c = "发送端返回 HTTP 500" in log
    d = log.count("toast 显示") >= 2
    print(f"A probe body readable           : {'PASS' if a else 'FAIL'}")
    print(f"B Ipv4DS prints body as text    : {'PASS' if b else 'FAIL'}")
    print(f"C non-2xx branch fires (HTTP500): {'PASS' if c else 'FAIL'}")
    print(f"D toast fires on BOTH casts     : {'PASS' if d else 'FAIL'}"
          f" (toast 显示 x{log.count('toast 显示')})")
    print("OVERALL:", "PASS" if (a and b and c and d) else "FAIL")
    return 0 if (a and b and c and d) else 1


if __name__ == "__main__":
    sys.exit(main())
