# Third-Party Notices

PhairPlay is distributed under the Apache License 2.0 (see [LICENSE](LICENSE)).
It also contains code and behavior ported from the projects below. Their
licenses and attributions are reproduced here as required.

---

## weekdayjast/MiracastReceiver

Source: <https://github.com/weekdayjast/MiracastReceiver>
License: MIT

Ported into this project:

- `app/src/main/kotlin/com/phairplay/cast/` — Cast V2 wire protocol, device
  authentication, mDNS responder, connection handler and Default Media
  Receiver session (`CastWire`, `CastIdentity`, `CastSession`,
  `CastMdnsPacket`, `CastMdnsAdvertiser`, `GoogleCastReceiver`)
- `app/src/main/kotlin/com/phairplay/miracast/` — Wi-Fi Direct management, WFD
  RTSP Sink, RTP receiver, MPEG-TS demuxer, H.264/HEVC video renderer and
  AAC/LPCM audio player
- `app/src/main/cpp/wfdctl.c` and its CMake target — root-only WFD IE helper

The port was adapted to PhairPlay's logging, surface injection, decoder and
settings APIs; the protocol logic follows the original.

```
MIT License

Copyright (c) weekdayjast

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Behavior references (no code copied)

The following projects were read to understand protocol behavior. No source
code was copied from them.

- CastHub / yukarinoki/openchromecast — MIT (early behavior references; the
  original repositories are no longer reachable)
- [emilsvennesson/vibecast](https://github.com/emilsvennesson/vibecast) — MIT
- [openairplay/airplay-spec](https://github.com/openairplay/airplay-spec) — protocol documentation
- [UxPlay](https://github.com/FDH2/UxPlay) — GPL-3.0; behavior reference only
- [RPiPlay](https://github.com/FD-/RPiPlay) — GPL-3.0; behavior reference only

---

## Runtime dependencies

| Component | License |
|-----------|---------|
| Kotlin standard library, kotlinx.coroutines | Apache License 2.0 |
| AndroidX (appcompat, core, leanback, lifecycle, datastore) | Apache License 2.0 |
| AndroidX Media3 / ExoPlayer | Apache License 2.0 |
| Bouncy Castle (`bcprov-jdk18on`) | MIT-style Bouncy Castle License |
| Gson | Apache License 2.0 |
| Timber | Apache License 2.0 |
| jUPnP (Cling fork) | CDDL-1.0 |
| SLF4J 1.7 + slf4j-android | MIT |
| `dev.dd.plist` (dd-plist) | MIT |
| Google Play services Cast TV (Google TV flavor only) | Android Software Development Kit License |

Full license texts for Gradle dependencies are available in each artifact's
POM or source distribution.
