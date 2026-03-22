# PhairPlay – Technical Specification

Version: 1.0
Status: Draft
Date: 2026-03-22

---

## 1. AirPlay 2 Protocol Overview

AirPlay 2 Screen Mirroring works as a layered protocol stack. Here is what happens from the moment a macOS user clicks "AirPlay" to video appearing on the TV:

### Step 1 – Service Discovery (mDNS / Bonjour)

macOS continuously scans the local network for AirPlay receivers using **mDNS** (Multicast DNS, RFC 6762), the same technology as Apple's "Bonjour".

PhairPlay registers two mDNS services:

| Service Type | Purpose |
|---|---|
| `_airplay._tcp` | Main AirPlay service — advertises device name, features, model |
| `_raop._tcp` | Remote Audio Output Protocol — required for audio streaming negotiation |

Each service registration includes **TXT records** that tell the sender what the receiver supports:

| TXT Key | Value | Meaning |
|---|---|---|
| `deviceid` | MAC address (e.g., `aa:bb:cc:dd:ee:ff`) | Unique device identifier |
| `features` | Bitmask (e.g., `0x5A7FFFF7,0x1E`) | Which AirPlay features are supported |
| `model` | `AppleTV5,3` | Tells macOS to treat us like an Apple TV |
| `srcvers` | `220.68` | AirPlay server version |
| `vv` | `2` | Protocol version 2 |
| `pi` | UUID string | Persistent device UUID |
| `pk` | 64-byte hex public key | Ed25519 public key (used in authenticated mode) |

**Android API used:** `android.net.nsd.NsdManager` — Android's built-in mDNS implementation.

### Step 2 – RTSP Session Establishment

Once macOS discovers the device, it opens a **TCP connection to port 7000** and speaks **RTSP** (Real Time Streaming Protocol, RFC 2326) with Apple-specific extensions.

The RTSP handshake sequence for screen mirroring:

```
macOS                           PhairPlay (Android TV)
  │                                     │
  │── OPTIONS rtsp://... RTSP/1.0 ────► │  "What can you do?"
  │◄─ 200 OK (Public: OPTIONS, SETUP…)─ │  "Here are my capabilities"
  │                                     │
  │── ANNOUNCE rtsp://... RTSP/1.0 ───► │  "I'm about to send you a stream"
  │   Content-Type: application/sdp     │  (SDP describes codec, ports, keys)
  │◄─ 200 OK ──────────────────────────│
  │                                     │
  │── SETUP rtsp://... RTSP/1.0 ──────► │  "Set up the video channel"
  │   Transport: RTP/AVP/TCP;...        │  (negotiates port numbers)
  │◄─ 200 OK (Transport: ...) ─────────│
  │                                     │
  │── SETUP rtsp://... RTSP/1.0 ──────► │  "Set up the audio channel"
  │◄─ 200 OK ──────────────────────────│
  │                                     │
  │── RECORD rtsp://... RTSP/1.0 ─────► │  "Start streaming now"
  │◄─ 200 OK ──────────────────────────│
  │                                     │
  │   [RTP video packets over TCP] ───► │
  │   [RTP audio packets over UDP] ───► │
  │   [Timing packets over UDP] ──────► │
  │                                     │
  │── TEARDOWN rtsp://... RTSP/1.0 ───► │  "Stop, I'm disconnecting"
  │◄─ 200 OK ──────────────────────────│
```

**SDP (Session Description Protocol)** in the ANNOUNCE body describes:
- Video codec: H.264 (`a=rtpmap:96 H264/90000`)
- Video parameters: profile-level-id, SPS/PPS (codec initialization data)
- Audio codec: AAC-ELD or ALAC
- Encryption keys (AES-128-CTR for audio, AES-128-CBC for video)
- Port numbers for RTP/RTCP

### Step 3 – Video Streaming

After RECORD, macOS sends video as **RTP packets over the RTSP TCP connection** (interleaved in the RTSP stream using `$` framing).

Each video packet contains a fragment of an **H.264 NAL unit** (Network Abstraction Layer — the elementary unit of H.264 video). The MediaCodec decoder reassembles NAL units into frames.

**Video path:**
```
RTP bytes (TCP) → RtspHandler strips RTP header →
VideoDecoder extracts NAL units → MediaCodec (hardware) → SurfaceView
```

**H.264 profile:** Baseline or Main profile, level 4.0 or lower.

### Step 4 – Audio Streaming

Audio is sent as **RTP packets over a separate UDP socket** (port negotiated in SETUP).

Audio format is either:
- **AAC-ELD** (Enhanced Low Delay AAC) — default for screen mirroring
- **ALAC** (Apple Lossless) — higher quality, higher bandwidth

Audio packets are **AES-128-CTR encrypted**. The key and IV are provided in the SDP body of the ANNOUNCE request.

**Audio path:**
```
RTP bytes (UDP) → AES-128-CTR decrypt → AudioDecoder extracts AAC/ALAC frame →
AudioTrack (hardware output)
```

### Step 5 – Timing Synchronization

A separate UDP socket (timing port) handles **NTP-based time synchronization** between sender and receiver. This is used to keep audio and video in sync.

---

## 2. System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        PhairPlay App                            │
│                                                                 │
│  ┌──────────────┐    ┌──────────────────────────────────────┐  │
│  │  MainActivity │    │          AirPlayReceiver             │  │
│  │  (UI layer)   │◄──►│  (orchestrates all components)       │  │
│  └──────┬───────┘    └──────────────────────────────────────┘  │
│         │                    │           │           │          │
│  ┌──────▼───────┐   ┌────────▼──┐  ┌────▼─────┐  ┌─▼───────┐ │
│  │ WaitingScreen│   │ MdnsService│  │RtspHandler│  │ Logger  │ │
│  │ StreamScreen │   │ (discovery)│  │(protocol) │  │ Network │ │
│  └──────────────┘   └───────────┘  └─────┬─────┘  │  Utils  │ │
│                                           │        └─────────┘ │
│                                    ┌──────┴──────┐             │
│                                    │             │             │
│                             ┌──────▼───┐  ┌─────▼──────┐     │
│                             │VideoDecoder│  │AudioPlayer │     │
│                             │(MediaCodec)│  │(AudioTrack)│     │
│                             └──────┬───┘  └─────┬──────┘     │
│                                    │             │             │
└────────────────────────────────────┼─────────────┼─────────────┘
                                     │             │
                              ┌──────▼───────────────────┐
                              │   Android OS / Hardware    │
                              │  (MediaCodec GPU decoder,  │
                              │   AudioFlinger, SurfaceView)│
                              └────────────────────────────┘

Data Flow (left to right = sender to receiver):
macOS ──[mDNS]──► MdnsService
macOS ──[RTSP/TCP]──► RtspHandler ──► VideoDecoder ──► SurfaceView
macOS ──[RTP/UDP]──► RtspHandler ──► AudioPlayer ──► AudioTrack
```

---

## 3. Component Responsibilities

| Component | File | Responsibility |
|---|---|---|
| `MainActivity` | `MainActivity.kt` | App entry point, lifecycle management, connects UI to AirPlayReceiver |
| `AirPlayReceiver` | `airplay/AirPlayReceiver.kt` | Top-level orchestrator: starts/stops mDNS, RTSP, manages state |
| `MdnsService` | `airplay/MdnsService.kt` | Registers and unregisters mDNS services via NsdManager |
| `RtspHandler` | `airplay/RtspHandler.kt` | Parses RTSP messages, handles the session state machine, distributes media data |
| `VideoDecoder` | `airplay/VideoDecoder.kt` | Configures MediaCodec for H.264, feeds NAL units, outputs to Surface |
| `AudioPlayer` | `airplay/AudioPlayer.kt` | Decrypts audio, decodes AAC/ALAC, plays via AudioTrack |
| `WaitingScreen` | `ui/WaitingScreen.kt` | Composable / View for the idle state UI |
| `StreamingScreen` | `ui/StreamingScreen.kt` | Composable / View holding the SurfaceView for video output |
| `Logger` | `util/Logger.kt` | Thin wrapper around Timber; adds tags, sanitizes sensitive data |
| `NetworkUtils` | `util/NetworkUtils.kt` | Reads device IP, MAC address, network interface info |

---

## 4. Libraries

Only libraries that cannot be replaced by Android built-in APIs are included.

| Library | Version | Justification | Alternative Considered |
|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8.x | Structured concurrency for all async I/O; part of Kotlin ecosystem | Java threads — no, too low-level; RxJava — no, too heavy |
| `com.jakewharton.timber:timber` | 5.0.x | Tagged, level-filtered logging with pluggable backends | Log directly — no, hard to filter in release builds |
| `org.bouncycastle:bcprov-jdk15on` | 1.77 | AES-128-CTR for audio decryption; SRP-6a for future pairing | Android's javax.crypto — partial support, missing SRP |

**Deliberately excluded:**
- No Retrofit/OkHttp (we write raw TCP/UDP sockets)
- No Room/SQLite (no persistence needed)
- No Jetpack Compose (use View-based UI for max TV compatibility)
- No Hilt/Dagger (manual DI is sufficient at this scale)

---

## 5. Security Concept

### 5.1 Input Validation
Every byte received from the network is treated as potentially malicious:
- **RTSP messages**: Maximum message size enforced (64 KB). Fields parsed with strict regex/parsers. Unknown methods return `501 Not Implemented`.
- **SDP body**: Length validated before parsing. Keys extracted with byte-length assertions.
- **RTP packets**: Header length validated. Payload size checked against packet length before memcopy.
- **Encryption keys**: Length asserted to be exactly 16 bytes (AES-128) before use.

### 5.2 Permissions Minimization
No permission is requested that is not strictly required. Specifically:
- `RECORD_AUDIO` — NOT requested (we receive audio, we do not record it)
- `READ_EXTERNAL_STORAGE` — NOT requested
- `CAMERA` — NOT requested
- No `MANAGE_EXTERNAL_STORAGE`, no `INSTALL_PACKAGES`, no system-level permissions

### 5.3 No Secrets in Code
- Device ID (MAC address) is read at runtime from the network interface — never hardcoded.
- AES keys are received per-session in the RTSP ANNOUNCE body — never stored persistently.
- No API keys, tokens, or passwords anywhere in the codebase.

### 5.4 Exception Handling Policy
- Every `try/catch` block MUST log the exception via `Logger`.
- Caught exceptions MUST trigger a graceful state reset (return to waiting state) — never ignore.
- `OutOfMemoryError` and critical JVM errors are not caught; they propagate to crash the app (fail fast).

### 5.5 Authentication (v1)
v1.0 uses **unauthenticated mode** (no PIN pairing required). The `features` TXT record bitmask is set to advertise that authentication is not required. This matches the behavior of open AirPlay receivers.

Future versions may implement HAP-based SRP-6a pairing (the infrastructure in `AirPlayReceiver` is designed to support this extension).

---

## 6. Performance Goals & Strategies

| Goal | Target | Strategy |
|---|---|---|
| Video latency | ≤ 100 ms | Direct MediaCodec surface output; no intermediate buffer copies |
| Frame rate | ≥ 25 fps | Hardware decode only; I/O on background coroutine; UI on main thread |
| RAM usage | ≤ 150 MB | Fixed-size ring buffers for RTP; MediaCodec manages its own buffers |
| CPU usage | ≤ 30% avg | MediaCodec offloads to GPU; coroutines avoid busy-waiting |
| A/V sync | ≤ 40 ms drift | NTP-based presentation timestamps fed to MediaCodec |

### Thread Model
```
[Main Thread]      UI updates, Surface creation/destruction
[IO Dispatcher]    TCP/UDP socket reads, RTSP parsing
[Default Dispatcher] RTP packet processing, decryption
[MediaCodec]       Runs on its own internal thread (hardware)
[AudioTrack]       Runs on its own internal thread (hardware)
```

No network operation may run on the Main Thread. No UI operation may run off the Main Thread.

---

## 7. Build Flavors

Two product flavors are configured from day one:

| Flavor | applicationId | minSdk | Notes |
|---|---|---|---|
| `googletv` | `com.phairplay.googletv` | 29 | Google TV / Android TV, Leanback UI, may use newer APIs |
| `firetv` | `com.phairplay.firetv` | 25 | Amazon Fire TV, must avoid Google-only APIs |

Shared code lives in `app/src/main/`. Flavor-specific overrides live in `app/src/googletv/` and `app/src/firetv/`.

The `firetv` flavor MUST NOT use any API gated on API level 26+ without a version check at runtime.

---

## 8. Supported AirPlay Feature Flags

The `features` TXT record is a bitmask that tells macOS which AirPlay capabilities the receiver has. PhairPlay v1.0 will advertise the following flags (based on the open AirPlay spec):

| Bit | Feature | PhairPlay v1 |
|---|---|---|
| 0 | Video | ✅ Supported |
| 1 | Photo | ❌ Not supported |
| 2 | VideoFairPlay | ❌ Not supported |
| 5 | Screen | ✅ Supported (mirroring) |
| 6 | Screen Rotate | ✅ Supported |
| 7 | Audio | ✅ Supported |
| 9 | AudioRedundant | ✅ Supported |
| 14 | AudioSyncedVideo | ✅ Supported |
| 23 | HasUnifiedAdvertiserInfo | ✅ Supported |
| 26 | SupportsAirPlayVideoV2 | ✅ Supported |
| 27 | MetaDataFeatures_0 | ✅ Supported |

The resulting hex value for the `features` field: `0x5A7FFFF7,0x1E` (will be refined during implementation).
