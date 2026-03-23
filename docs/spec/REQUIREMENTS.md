# PhairPlay – Requirements

Version: 2.1
Status: Draft
Date: 2026-03-23

---

## 1. Functional Requirements

### 1.1 Service Discovery & Advertising

- FR-01: The app MUST advertise an AirPlay 2 receiver via mDNS (`_airplay._tcp`, `_raop._tcp`).
- FR-02: The app MUST advertise a Miracast receiver via Wi-Fi Direct (P2P) service discovery.
- FR-03: The app MUST optionally advertise a Google Cast receiver (if Cast SDK is registered).
- FR-04: All three services can be enabled/disabled independently in Settings.
- FR-05: The device name shown in sender pickers MUST be configurable in Settings (defaults to Android device name).
- FR-06: All services start advertising within 2 seconds of app or service launch.
- FR-07: All services stop advertising within 5 seconds of being disabled or app close.

### 1.2 AirPlay 2 Receiver

- FR-08: Accept Screen Mirroring connections from **macOS 12+** and **iOS/iPadOS 13+** via RTSP on TCP port 7000.
- FR-09: Complete the RTSP handshake (OPTIONS → SETUP → ANNOUNCE → RECORD) without errors.
- FR-10: Reject second concurrent AirPlay connection with RTSP 503.
- FR-11: Auto-reconnect and resume advertising after connection loss.
- FR-12 *(new)*: Accept **audio-only AirPlay** streams (music, podcasts) from macOS and iOS senders, playing audio through the TV speakers without requiring a video stream.

### 1.3 Miracast Receiver

- FR-13: Discover and accept incoming Miracast (Wi-Fi Display / WFD) connection requests.
- FR-14: Negotiate the WFD session (capability exchange, RTSP-WFD handshake).
- FR-15: Decode and display the incoming H.264 video stream from the Miracast sender.
- FR-16: Play audio from the Miracast stream (PCM/AAC via HDMI).
- FR-17: Support Windows 10+, Android, and Samsung Galaxy as Miracast senders (Phase 3+).
- FR-18: Show the Miracast connection state on the HomeScreen status card.

### 1.4 Google Cast Receiver

- FR-19: Run as a Cast Custom Receiver application registered with the Google Cast SDK.
- FR-20: Accept Cast connections from Chrome/Android/macOS/iOS senders.
- FR-21: Support Cast screen mirroring from Chrome and Android senders.
- FR-22: Display Cast status on the HomeScreen status card.
- FR-23: Gracefully degrade if the Cast SDK is unavailable (missing Google Play Services on Fire TV).

### 1.5 Service Control

- FR-24: The user MUST be able to **start**, **stop**, and **restart** all receiver services from the app UI.
- FR-25: The receiver runs as an Android **ForegroundService** so it continues operating when the app is in the background (e.g., screensaver active).
- FR-26: A **persistent notification** MUST be shown while the ForegroundService is running, with quick-action buttons: Stop, Restart.
- FR-27: Service state (running/stopped/error) MUST be visible on the HomeScreen at all times.
- FR-28: Stopping the service MUST release all network ports, close all connections, and stop advertising.
- FR-29: Restarting the service MUST perform a clean stop followed by a clean start within 3 seconds.

### 1.6 Settings

- FR-30: A dedicated **Settings screen** MUST be accessible from the HomeScreen.
- FR-31: Settings MUST be persisted between app restarts (Android DataStore / SharedPreferences).
- FR-32: The following settings MUST be configurable:

| Setting | Default | Description |
|---|---|---|
| Display name | Android device name | Name shown in sender pickers |
| AirPlay enabled | ON | Enable/disable AirPlay service |
| Miracast enabled | ON | Enable/disable Miracast service |
| Cast enabled | ON | Enable/disable Cast service |
| AirPlay PIN auth | OFF | Require PIN for AirPlay connections |
| Start on boot | OFF | Auto-start service on device boot |
| Show debug overlay | OFF | Show FPS / latency overlay (dev) |

### 1.7 Video / Audio

- FR-33: Decode H.264 video using hardware MediaCodec (H.265/HEVC to be evaluated in v2, see §3).
- FR-34: Display decoded video full-screen maintaining aspect ratio.
- FR-35: Play audio in sync with video (A/V drift ≤ 40ms).
- FR-36: For **audio-only** streams (no video): play through the default audio output without launching the streaming screen.
- FR-37: Return to HomeScreen (or remain on HomeScreen for audio-only) when stream ends.

### 1.8 Internationalization (i18n)

- FR-38: All user-visible strings MUST be defined in Android resource files (never hardcoded in Kotlin/XML).
- FR-39: The app MUST support at least **English** and **German** at launch.
- FR-40: The i18n structure MUST allow adding new languages without changing Kotlin or layout files.
- FR-41: Date/time formats, number formats, and text direction MUST respect the device locale.

---

## 2. Non-Functional Requirements

### 2.1 Performance (unchanged)
- NFR-01: Video latency ≤ 100ms on local 5 GHz Wi-Fi.
- NFR-02: Frame rate ≥ 25fps sustained.
- NFR-03: Peak RAM ≤ 150 MB (single active stream).
- NFR-04: Average CPU ≤ 30% on mid-range TV SoC.

### 2.2 UI / UX
- NFR-05: UI design MUST follow the **Google TV Streamer** design language: dark background, card-based layout, large focus indicators, D-pad navigable.
- NFR-06: All interactive elements MUST be focusable via D-pad / remote control.
- NFR-07: Minimum touch target and focus target size: 48dp.
- NFR-08: Text minimum size: 18sp for body, 32sp for titles.
- NFR-09: Sufficient contrast ratio for TV viewing from 3 meters.
- NFR-10: The HomeScreen MUST show the active protocol, sender name, and duration within 1 second of change.

### 2.3 Privacy & Security (unchanged)
- NFR-11: No ads, no analytics, no telemetry.
- NFR-12: All network input validated before processing.
- NFR-13: No hardcoded secrets.
- NFR-14: Minimal Android permissions.

### 2.4 Compatibility
- NFR-15: Google TV: Android 10+ (API 29+), ARMv8.
- NFR-16: Fire TV: Android 7.1+ (API 25+), ARMv7/ARMv8.
- NFR-17: AirPlay sender (screen mirroring): macOS 12+, iOS/iPadOS 13+.
- NFR-18: AirPlay sender (audio-only): macOS 12+, iOS/iPadOS 13+.
- NFR-19: Miracast sender: Windows 10+, Android 4.2+.
- NFR-20: Cast sender: Chrome 72+, Android 5+.

### 2.5 Code Quality
- NFR-23: No source file exceeds 400 lines.
- NFR-24: Every public method has at least one unit test.
- NFR-25: All classes have KDoc headers.

---

## 3. Explicitly Excluded Features (v1)

| Feature | Reason | Future |
|---|---|---|
| FairPlay DRM content | Apple license required | Not planned |
| HomeKit / HAP pairing | Complex, separate protocol | v3 roadmap |
| WiDi (Intel) | EOL technology | Not planned |
| DLNA / UPnP | Out of scope | Not planned |
| Cloud / remote streaming | Security risk | Not planned |
| Screen recording to file | Privacy concern | Not planned |
| H.265 / HEVC decode | Not in AirPlay 2 mirror v1 spec | v2 evaluation |
| AV1 / VP9 decode | Not used by AirPlay or Miracast currently | v2 evaluation |
| AirPlay audio grouping (multi-room) | Requires AirPlay 2 full stack | v3 roadmap |

> **Note on codecs:** AirPlay screen mirroring currently uses H.264 (Baseline/Main profile). Apple has not publicly released an H.265-based mirroring spec. If Apple adds H.265 support in a future macOS/iOS version, PhairPlay v2 will add `MediaCodec` decoding for `video/hevc` behind a device capability check (hardware support is required — no software fallback).

---

## 4. Target Devices & Senders

### Receivers (this app)
| Platform | Min OS | API | Arch |
|---|---|---|---|
| Google TV (Chromecast with Google TV) | Android 10 | 29 | ARMv8 |
| Amazon Fire TV Stick 4K | Android 7.1 | 25 | ARMv8 |
| Amazon Fire TV Stick 3rd gen | Android 7.1 | 25 | ARMv7/ARMv8 |

### Senders (clients)
| Protocol | Platform | Version | Notes |
|---|---|---|---|
| AirPlay 2 | macOS | 12 (Monterey)+ | Screen mirroring + audio-only |
| AirPlay 2 | iOS / iPadOS | 13+ | Screen mirroring + audio-only |
| Miracast | Windows | 10+ | Screen mirroring |
| Miracast | Android | 4.2+ | Screen mirroring |
| Google Cast | Chrome browser | 72+ | Tab/screen cast |
| Google Cast | Android | 5+ | Screen cast |
