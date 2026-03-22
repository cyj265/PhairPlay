# PhairPlay Architecture

This document explains how PhairPlay works — from the moment a macOS user clicks "AirPlay" to the moment video appears on the TV.

---

## How Does AirPlay 2 Work? (Simple Explanation)

Imagine you want to show your Mac's screen on your TV. AirPlay 2 makes this happen in three steps:

### Step 1: Your TV announces itself ("I'm here!")

When PhairPlay starts, it uses a technology called **mDNS** (Multicast DNS) — the same thing as Apple's "Bonjour" — to broadcast a message on your local network. This message says:

> "Hello! I'm a TV called 'My Living Room TV', I support AirPlay 2, and I'm listening on port 7000."

Your Mac hears this message automatically and adds the TV to its AirPlay menu.

### Step 2: Your Mac and TV shake hands ("Let's agree on how to stream")

When you click the TV in the AirPlay menu, your Mac opens a connection to port 7000 and starts a "negotiation" using a protocol called **RTSP** (Real Time Streaming Protocol). Think of it as a conversation:

- Mac: "Hello, what can you do?" → TV: "I can receive video and audio."
- Mac: "Great. I'll send H.264 video and AAC audio, encrypted with this key: [key]." → TV: "OK, got it."
- Mac: "Set up a channel for video on port X." → TV: "Ready."
- Mac: "Start streaming!" → TV: "Go ahead!"

### Step 3: Video and audio flow

After the handshake, your Mac starts sending video frames (H.264 encoded) and audio samples (AAC encoded) as **RTP packets** (tiny network packets). The TV:

1. Receives the packets
2. Decrypts the audio using the key from Step 2
3. Passes the video through the hardware decoder (GPU)
4. Displays the video full-screen
5. Plays the audio through the speakers

When you stop screen sharing, the Mac sends a "goodbye" message (RTSP TEARDOWN) and the TV returns to the waiting screen.

---

## Component Overview

| Component | File | What It Does |
|---|---|---|
| `PhairPlayApp` | `PhairPlayApp.kt` | App-level init (logging setup) |
| `MainActivity` | `MainActivity.kt` | Hosts the UI, manages app lifecycle |
| `AirPlayReceiver` | `airplay/AirPlayReceiver.kt` | **Orchestrator** — starts/stops all services |
| `MdnsService` | `airplay/MdnsService.kt` | mDNS advertising (Step 1 above) |
| `RtspHandler` | `airplay/RtspHandler.kt` | RTSP session (Step 2 above) |
| `VideoDecoder` | `airplay/VideoDecoder.kt` | Hardware H.264 decode → screen |
| `AudioPlayer` | `airplay/AudioPlayer.kt` | Audio decrypt + play |
| `WaitingScreen` | `ui/WaitingScreen.kt` | Idle UI — "Ready for AirPlay" |
| `StreamingScreen` | `ui/StreamingScreen.kt` | Streaming UI — video surface |
| `Logger` | `util/Logger.kt` | Logging wrapper (Timber) |
| `NetworkUtils` | `util/NetworkUtils.kt` | Reads device name, MAC, IP |

---

## Full Data Flow Diagram

```
  macOS (sender)                          Android TV (PhairPlay)
  ─────────────                           ──────────────────────

  [App starts on TV]
                                          PhairPlayApp.onCreate()
                                               │
                                          MainActivity.onCreate()
                                               │
                                          AirPlayReceiver.start()
                                           ├── MdnsService.start()
                                           │    └── NsdManager.registerService(_airplay._tcp)
                                           │    └── NsdManager.registerService(_raop._tcp)
                                           └── RtspHandler.start()
                                                └── ServerSocket.accept() [waiting...]
                                          WaitingScreen shown

  ─────── DISCOVERY ─────────────────────────────────────────────────────

  [User opens AirPlay menu on Mac]

  mDNS query (multicast)  ──────────────► NsdManager responds automatically
  ◄── mDNS response ────────────────────── ("PhairPlay" at 192.168.1.x:7000)

  AirPlay device appears in macOS menu ✓

  ─────── HANDSHAKE ──────────────────────────────────────────────────────

  [User clicks "PhairPlay" in AirPlay menu]

  TCP connect → port 7000 ─────────────► RtspHandler.accept()
  OPTIONS rtsp://... ──────────────────► handleOptions() → 200 OK
  ANNOUNCE (SDP body) ─────────────────► handleAnnounce()
                                               │ parsesSDP:
                                               │  - H.264 codec params (SPS/PPS)
                                               │  - Audio codec (AAC-ELD/ALAC)
                                               │  - AES-128 key + IV (for audio)
                                               └─ → 200 OK
  SETUP (video channel) ───────────────► handleSetup() → 200 OK + port
  SETUP (audio channel) ───────────────► handleSetup() → 200 OK + port
  RECORD ──────────────────────────────► handleRecord()
                                               │
                                          onStreamingStarted() called
                                               │
                                          VideoDecoder.initialize(SPS, PPS)
                                          AudioPlayer.initialize(key, IV)
                                          StreamingScreen shown (WaitingScreen hidden)

  ─────── STREAMING ──────────────────────────────────────────────────────

  [macOS is now mirroring its screen]

  RTP video packet (TCP, interleaved) ─► RtspHandler reads RTP frames
                                               └── VideoDecoder.decodeNalUnit()
                                                    └── MediaCodec (hardware GPU)
                                                         └── → SurfaceView (display)

  RTP audio packet (UDP) ──────────────► AudioPlayer.playAudioPacket()
                                               ├── Strip RTP header
                                               ├── AES-128-CTR decrypt
                                               ├── Parse AAC/ALAC frame
                                               └── AudioTrack.write() → speakers

  NTP timing packet (UDP) ─────────────► TimingHandler (keeps A/V in sync)

  ─────── DISCONNECT ─────────────────────────────────────────────────────

  [User stops screen sharing on Mac]

  TEARDOWN rtsp://... ─────────────────► handleTeardown()
                                               │
                                          onStreamingStopped() called
                                               │
                                          VideoDecoder.release()
                                          AudioPlayer.release()
                                          StreamingScreen hidden
                                          WaitingScreen shown
                                          MdnsService.restart() (re-advertise)
```

---

## Design Decisions

### Why View-based UI instead of Jetpack Compose?

Jetpack Compose is modern, but it adds complexity for TV apps:
- Focus handling with D-pad is harder to get right in Compose
- SurfaceView (needed for low-latency video) has quirks in Compose
- The `AppCompat.Leanback` theme (required for TV launcher integration) works better with traditional Views

For v1.0, View-based UI with ConstraintLayout is simpler and more compatible with TV devices.

### Why no ViewModel/LiveData/Flow for state?

With only two states (WAITING, STREAMING), a ViewModel would add complexity without benefit. The state is managed directly in `AirPlayReceiver` with a callback to `MainActivity`. If the app grows to need more complex state, a ViewModel can be introduced later.

### Why Bouncy Castle for cryptography?

Android's built-in `javax.crypto` supports AES-128-CTR, but:
- The API for it is verbose
- SRP-6a (needed for future PIN pairing) is not available in Android's built-in crypto
- Bouncy Castle is a well-audited, widely-used crypto library

### Why raw sockets instead of a networking library?

AirPlay uses a custom RTSP variant with Apple extensions. No off-the-shelf HTTP/RTSP library handles these extensions correctly. Implementing the protocol with raw Java sockets is more work initially, but gives full control over the protocol behavior — which is necessary for compatibility.

### Why one Activity?

- Simpler lifecycle management
- The MediaCodec Surface doesn't need to be passed across Activity transitions
- TV apps typically use a single-Activity architecture anyway (Leanback recommends it)

---

## Security Architecture

### Input Validation Gates

Every external input passes through a validation gate before being processed:

```
Network bytes → [LENGTH CHECK] → [FORMAT CHECK] → [RANGE CHECK] → safe internal data
```

1. **LENGTH CHECK**: All RTSP messages are limited to 64 KB. Buffer sizes are checked before any array access.
2. **FORMAT CHECK**: RTSP method names are matched against an allowlist. Unknown methods return 501.
3. **RANGE CHECK**: Numeric values from the network (port numbers, timestamps) are validated to be in expected ranges.

### No Sensitive Data in Logs

The `Logger` wrapper is designed to be extended to redact IP addresses and keys from debug logs before a release build is published.

### Principle of Least Privilege

The app requests only 4 permissions, all of which are strictly necessary. No storage, no camera, no microphone.
