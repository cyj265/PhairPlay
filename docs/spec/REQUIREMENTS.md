# PhairPlay – Requirements

Version: 1.0
Status: Draft
Date: 2026-03-22

---

## 1. Functional Requirements

### 1.1 Service Discovery
- FR-01: The app MUST advertise itself as an AirPlay 2 receiver via mDNS using the service types `_airplay._tcp` and `_raop._tcp`.
- FR-02: The device name shown in the macOS AirPlay picker MUST match the Android TV device name (as set in system settings).
- FR-03: The app MUST start advertising within 2 seconds of launch.
- FR-04: The app MUST stop advertising when it is closed or moved to background for more than 30 seconds.

### 1.2 Connection Handling
- FR-05: The app MUST accept incoming AirPlay connections via RTSP (Apple-extended RFC 2326) on TCP port 7000.
- FR-06: The app MUST complete the RTSP handshake (OPTIONS → SETUP → ANNOUNCE → RECORD) without errors.
- FR-07: Only one sender may be connected at a time. Additional connection attempts MUST be rejected with an RTSP `503 Service Unavailable` response.
- FR-08: The app MUST automatically reconnect and resume advertising after a connection is dropped.

### 1.3 Screen Mirroring (Video)
- FR-09: The app MUST accept and decode an H.264 video stream sent by a macOS 12+ sender.
- FR-10: The decoded video MUST be displayed full-screen, maintaining the sender's aspect ratio (letterboxed if needed).
- FR-11: Video MUST be rendered using Android hardware decoding (MediaCodec). No software-only decode path.

### 1.4 Audio
- FR-12: The app MUST decode and play the audio stream sent alongside the video (AAC-ELD or ALAC format).
- FR-13: Audio MUST be played in sync with the video (A/V drift ≤ 40 ms).
- FR-14: Audio MUST be output through the TV's default audio output (HDMI / optical, whatever is default).

### 1.5 UI States
- FR-15: When idle (no sender connected), the app MUST display a "Waiting Screen" showing:
  - App name ("PhairPlay")
  - The device's AirPlay name
  - Brief instructions (e.g., "Open AirPlay on your Mac and select this device")
- FR-16: When a sender is connected and streaming, the app MUST switch to a full-screen "Streaming Screen" showing only the mirrored content.
- FR-17: When the stream ends, the app MUST return to the Waiting Screen.

### 1.6 Stability & Reconnect
- FR-18: After a connection loss (sender disconnects, network hiccup), the app MUST automatically resume advertising within 5 seconds.
- FR-19: The app MUST run stably for at least 30 minutes of continuous streaming without crashing or disconnecting.

---

## 2. Non-Functional Requirements

### 2.1 Performance
- NFR-01: Video latency (time from screen change on macOS to display on TV) MUST be ≤ 100 ms under typical local network conditions (Wi-Fi 5 GHz or Ethernet).
- NFR-02: Video playback MUST sustain ≥ 25 fps.
- NFR-03: Peak RAM usage during active streaming MUST NOT exceed 150 MB.
- NFR-04: Average CPU usage during active streaming MUST NOT exceed 30% on a mid-range TV SoC (e.g., Amlogic S905X3).

### 2.2 Privacy & Security
- NFR-05: The app MUST NOT contain any advertisement SDKs, analytics SDKs, or telemetry.
- NFR-06: The app MUST NOT require an internet connection. All functionality is local-network only.
- NFR-07: All data received over the network MUST be validated (length checks, format checks) before processing.
- NFR-08: No hardcoded IP addresses, credentials, or secrets.
- NFR-09: All exceptions MUST be caught, logged, and handled gracefully. No exception may be silently swallowed.

### 2.3 Permissions
The app MUST declare only the following Android permissions and no others:
- `INTERNET` — required for local TCP/UDP socket operations
- `CHANGE_WIFI_MULTICAST_STATE` — required for mDNS multicast
- `ACCESS_WIFI_STATE` — required to read device IP address and Wi-Fi info
- `ACCESS_NETWORK_STATE` — required to detect network changes

### 2.4 Compatibility
- NFR-10: **Google TV** — minimum Android 10 (API level 29), ARMv8 (64-bit).
- NFR-11: **Fire TV** — minimum Android 7.1 (API level 25), ARMv7 and ARMv8.
- NFR-12: The app MUST be compatible with AirPlay senders running macOS 12 (Monterey) and later.
- NFR-13: The app MUST function correctly when the TV is connected via Wi-Fi (2.4 GHz and 5 GHz) or Ethernet.

### 2.5 Code Quality
- NFR-14: No Kotlin source file may exceed 400 lines.
- NFR-15: Every public method MUST have at least one unit test.
- NFR-16: Every class MUST have a KDoc header comment explaining: what it does, why it exists, and how to use it.

---

## 3. Explicitly Excluded Features

The following features are **out of scope** for v1.0 and MUST NOT be added without a new specification review:

| Excluded Feature | Reason |
|---|---|
| Google Cast / Chromecast support | Different protocol, out of scope |
| iOS sender support | Adds complexity (FairPlay DRM, different negotiation); planned for v2 |
| Audio-only AirPlay (music streaming from macOS Music app) | Separate protocol flow; planned for v2 |
| FairPlay DRM content | Legal and technical complexity; not feasible without Apple license |
| HomeKit pairing / PIN-based pairing | Adds setup friction; PIN-less mode first |
| Mirroring from Windows, Linux, Android, or other platforms | Only macOS AirPlay 2 is targeted in v1 |
| Cloud / remote streaming (outside local network) | Security risk; requires internet |
| Alexa / Google Assistant integration | Out of scope |
| Settings UI / preferences screen | Unnecessary complexity for v1 |
| Picture-in-Picture mode | Out of scope for v1 |
| Screen recording of incoming stream | Privacy concern; explicitly excluded |
| Multiple simultaneous sender connections | One sender at a time is sufficient |

---

## 4. Target Devices

### 4.1 Primary Targets
| Platform | OS Version | API Level | Architecture |
|---|---|---|---|
| Google TV (e.g., Chromecast with Google TV) | Android 10+ | 29+ | ARMv8 (64-bit) |
| Amazon Fire TV Stick 4K | Android 7.1+ | 25+ | ARMv8 (64-bit) |
| Amazon Fire TV Stick (3rd gen) | Android 7.1+ | 25+ | ARMv7 + ARMv8 |

### 4.2 Target Sender
| Platform | Version | Feature |
|---|---|---|
| macOS | 12 (Monterey) and later | Screen Mirroring via AirPlay 2 |

### 4.3 Network Requirements
- Sender and receiver MUST be on the same local network (same subnet or mDNS-routed).
- Wi-Fi 5 GHz or Ethernet recommended for ≤100ms latency target.
- Wi-Fi 2.4 GHz is supported but may not meet the latency target under congestion.
