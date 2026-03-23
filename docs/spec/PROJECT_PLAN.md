# PhairPlay – Project Plan

Version: 2.0
Status: Draft
Date: 2026-03-23

---

## Phase Order

```
Phase 0 → Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5 → Phase 6 → Phase 7 → Phase 8 → Phase 9 → Phase 10
 Spec     Skeleton  AirPlay   AirPlay   AirPlay   Miracast    Cast     Stability  Fire TV   i18n    Release
          + UI       mDNS     Handshk    Video+    Receiver  Receiver             Port      Polish
                    +Service  +RTSP     Audio
  M0        M1       M2        M3        M4+M5      M6         M7        M8         M9       M10     M11
```

---

## Phase 0 – Specification ✅

**Milestone:** M0
**Status:** Complete

**Definition of Done:** All spec documents committed, reviewed, and updated for v2.0 scope.

---

## Phase 1 – Skeleton + Service Architecture + UI

**Milestone:** M1

**Goal:** App starts on both platforms. Google TV-style HomeScreen. ForegroundService running. Settings screen accessible. All three service status cards visible.

**Tasks:**
- [ ] `HomeFragment.kt` — Google TV Streamer-style home screen with 3 service cards
- [ ] `SettingsFragment.kt` — settings screen with toggles and text inputs
- [ ] `ServiceStatusCard.kt` — reusable card component showing protocol status
- [ ] `PhairPlayService.kt` — ForegroundService with start/stop/restart
- [ ] `ServiceController.kt` — start/stop/restart API
- [ ] `AppSettings.kt` — settings data model
- [ ] `SettingsRepository.kt` — DataStore persistence
- [ ] Persistent notification with Stop/Restart actions
- [ ] `res/values/strings.xml` (EN), `res/values-de/strings.xml` (DE)
- [ ] Unit tests for all new public methods

**Definition of Done:**
- App starts on both platforms without crash
- HomeScreen shows 3 service cards (AirPlay / Miracast / Cast) with status
- Settings screen opens and saves settings
- ForegroundService persists in background
- Notification visible with Stop/Restart buttons
- DE strings visible when device language is German

**Acceptance Criteria:** AC-1.x

---

## Phase 2 – mDNS + Network Visibility

**Milestone:** M2

**Goal:** All enabled services advertise on the network. macOS sees AirPlay. Windows sees Miracast. Chrome sees Cast.

**Tasks:**
- [ ] `MdnsService.kt` — AirPlay mDNS advertisement (complete implementation)
- [ ] `WifiDirectManager.kt` — Wi-Fi P2P manager for Miracast discovery
- [ ] `CastAdvertiser.kt` — Cast receiver advertisement stub
- [ ] `NetworkUtils.kt` — complete implementation (IP, MAC, UUID)
- [ ] Settings: device name applied to all advertisers

**Definition of Done:**
- macOS sees PhairPlay within 3s (AC-2.1)
- Device name from Settings is shown in picker
- WifiP2p service registered (Miracast P2P)
- Service cards update when advertising starts/stops

---

## Phase 3 – AirPlay Handshake (RTSP)

**Milestone:** M3

**Goal:** Full AirPlay RTSP session establishment end-to-end.

**Tasks:**
- [ ] `RtspHandler.kt` — full RTSP implementation (all methods, SDP parsing)
- [ ] SDP parser — extract H.264 params, AAC keys, port numbers
- [ ] Input validation — all fields, max sizes, format checks
- [ ] Unit tests: all RTSP methods, SDP edge cases, malformed input

**Definition of Done:** AC-3.x — full handshake from macOS without RTSP errors.

---

## Phase 4 – AirPlay Video

**Milestone:** M4

**Tasks:**
- [ ] `VideoDecoder.kt` — full MediaCodec H.264 implementation
- [ ] RTP video packet demuxing from RTSP TCP stream
- [ ] `StreamingScreen.kt` — full SurfaceView integration
- [ ] Aspect ratio / letterbox

**Definition of Done:** AC-4.x — ≥25fps, ≤100ms latency on Google TV.

---

## Phase 5 – AirPlay Audio

**Milestone:** M5

**Tasks:**
- [ ] `AudioPlayer.kt` — full AES-128-CTR decrypt + AudioTrack
- [ ] RTP audio UDP socket
- [ ] NTP timing sync

**Definition of Done:** AC-5.x — A/V sync ≤40ms.

---

## Phase 6 – Miracast Receiver

**Milestone:** M6

**Goal:** Miracast screen mirroring from Windows 10+ and Android.

**Tasks:**
- [ ] `WifiDirectManager.kt` — full Wi-Fi P2P (peer discovery, connection accept)
- [ ] `MiracastSession.kt` — WFD RTSP negotiation
- [ ] `MiracastDecoder.kt` — H.264 video decode (reuses VideoDecoder)
- [ ] `MiracastAudio.kt` — audio decode for WFD audio
- [ ] Miracast status card updates in HomeScreen

**Definition of Done:** AC-6.x — Windows 10 screen mirroring works end-to-end.

---

## Phase 7 – Google Cast Receiver

**Milestone:** M7

**Goal:** Google Cast screen mirroring from Chrome and Android.

**Tasks:**
- [ ] Register Cast application on Google Cast Developer Console
- [ ] `CastReceiverManager.kt` — Cast SDK integration
- [ ] Handle Cast media playback
- [ ] Graceful fallback on Fire TV (no Google Play Services)

**Definition of Done:** AC-7.x — Cast from Chrome works on Google TV.

---

## Phase 8 – Stability

**Milestone:** M8

**Tasks:**
- [ ] 30-minute continuous stream tests for all 3 protocols
- [ ] Auto-reconnect for all protocols
- [ ] Memory leak audit
- [ ] `start on boot` BroadcastReceiver

**Definition of Done:** AC-8.x — 30min stable, reconnect working.

---

## Phase 9 – Fire TV Port

**Milestone:** M9

**Tasks:**
- [ ] Test all phases on Fire TV hardware
- [ ] Fix API level 25 issues
- [ ] Cast graceful fallback (no GMS)
- [ ] Fire TV flavor specific adjustments

---

## Phase 10 – i18n Polish

**Milestone:** M10

**Tasks:**
- [ ] Complete all string resources in EN and DE
- [ ] Add FR strings (community contribution ready)
- [ ] RTL support baseline (for future AR/HE)
- [ ] Locale-aware date/time/number formatting

---

## Phase 11 – Release

**Milestone:** M11

**Tasks:**
- [ ] All tests green, CI green
- [ ] Signed release APKs for both flavors
- [ ] GitHub Release with both APKs
- [ ] Full documentation review
- [ ] CHANGELOG v1.0.0

---

## Milestone Summary

| # | Phase | Key Deliverable | Primary AC |
|---|---|---|---|
| M0 | Spec | 4 spec docs | AC-0.x |
| M1 | Skeleton | Service + UI scaffold | AC-1.x |
| M2 | Discovery | All 3 protocols advertising | AC-2.x |
| M3 | AirPlay Handshake | RTSP session | AC-3.x |
| M4 | AirPlay Video | H.264 ≥25fps | AC-4.x |
| M5 | AirPlay Audio | A/V sync ≤40ms | AC-5.x |
| M6 | Miracast | Windows screen mirror | AC-6.x |
| M7 | Cast | Chrome screen mirror | AC-7.x |
| M8 | Stability | 30min tests all protocols | AC-8.x |
| M9 | Fire TV | Fire TV all protocols | AC-9.x |
| M10 | i18n | EN+DE complete | AC-10.x |
| M11 | Release | Signed APKs, CI green | AC-11.x |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| AirPlay undocumented behavior | High | High | Reference UxPlay/RPiPlay |
| Miracast requires hidden Android APIs on some devices | High | High | Use WifiP2pManager + custom WFD; test early on real hardware |
| Cast not available on Fire TV (no GMS) | Certain | Medium | Graceful fallback; disable Cast toggle on Fire TV flavor |
| Wi-Fi P2P conflicts with existing AP connection | Medium | High | Test multi-connected scenarios early |
| Google Cast SDK license terms | Low | High | Review carefully before distribution |
| Open-source AirPlay implementations: legal risk | Low | High | Reference only for protocol understanding; write code from scratch |
