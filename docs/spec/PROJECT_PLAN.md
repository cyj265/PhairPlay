# PhairPlay – Project Plan

Version: 1.0
Status: Draft
Date: 2026-03-22

---

## Development Philosophy

1. **Spec before code.** No production code is written before Phase 0 is complete and reviewed.
2. **Milestones are gates.** The next phase only starts when the current phase's Definition of Done is fully met.
3. **Tests are not optional.** Every phase includes writing tests, not just at the end.
4. **Each commit should leave the codebase in a working state.** No "WIP" commits on the main branch.
5. **Small, focused changes.** Each pull request addresses one milestone or sub-task.

---

## Phase Overview

```
Phase 0 ──► Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 4 ──► Phase 5 ──► Phase 6 ──► Phase 7 ──► Phase 8
 Spec       Skeleton    Discovery   Handshake    Video       Audio      Stability   Fire TV     Release
  M0          M1          M2          M3          M4          M5          M6          M7          M8
```

---

## Phase 0 – Specification

**Milestone:** M0

**Goal:** Establish a clear, shared understanding of what PhairPlay is and how it will be built. All technical decisions are made at this stage, not during coding.

**Tasks:**
- [x] Write `docs/spec/REQUIREMENTS.md`
- [x] Write `docs/spec/TECHNICAL_SPEC.md`
- [x] Write `docs/spec/ACCEPTANCE_CRITERIA.md`
- [x] Write `docs/spec/PROJECT_PLAN.md`
- [ ] Review and approve all 4 documents
- [ ] Set up repository structure (README, LICENSE, .gitignore, Gradle files)
- [ ] Set up GitHub Actions CI/CD pipeline

**Definition of Done:**
- All 4 spec documents are committed to the repository.
- Repository structure is in place.
- CI pipeline runs and is green (even if there's nothing to build yet).

**Acceptance Criteria:** AC-0.1 through AC-0.5 (see ACCEPTANCE_CRITERIA.md)

---

## Phase 1 – App Skeleton

**Milestone:** M1

**Goal:** A buildable Android app that starts without crashing on both target platforms and shows a basic Waiting Screen.

**Tasks:**
- [ ] `MainActivity.kt` — lifecycle management, connect to `AirPlayReceiver`
- [ ] `AirPlayReceiver.kt` — stub with start/stop lifecycle methods
- [ ] `WaitingScreen.kt` — UI showing device name and instructions
- [ ] `StreamingScreen.kt` — placeholder for future video surface
- [ ] `AndroidManifest.xml` — permissions, activity declarations
- [ ] `strings.xml` — device name reading, UI strings
- [ ] `activity_main.xml` — layout switching between screens
- [ ] Flavor overrides: `googletv/` and `firetv/` res directories
- [ ] Unit tests for each new public method

**Definition of Done:**
- `./gradlew assembleDebug` exits with code 0 for both flavors.
- App launches on Google TV and Fire TV without crashing.
- WaitingScreen shows the correct device name.
- All unit tests pass.

**Acceptance Criteria:** AC-1.1 through AC-1.8

---

## Phase 2 – Network Visibility (mDNS)

**Milestone:** M2

**Goal:** macOS AirPlay picker discovers PhairPlay within 3 seconds.

**Tasks:**
- [ ] `MdnsService.kt` — NsdManager-based mDNS service registration
  - Register `_airplay._tcp` with correct TXT records
  - Register `_raop._tcp` with correct TXT records
  - Handle registration failures and retry
  - Unregister on stop
- [ ] `NetworkUtils.kt` — read device MAC address and IP address
- [ ] Connect `MdnsService` to `AirPlayReceiver` lifecycle
- [ ] `MdnsServiceTest.kt` — unit tests for TXT record content, service types
- [ ] `NetworkUtilsTest.kt` — unit tests for IP/MAC reading

**Definition of Done:**
- macOS AirPlay picker shows the device name within 3 seconds.
- Device disappears from picker within 10 seconds of app close.
- All unit tests pass.

**Acceptance Criteria:** AC-2.1 through AC-2.7

---

## Phase 3 – RTSP Handshake

**Milestone:** M3

**Goal:** Full RTSP session establishment (OPTIONS → SETUP → ANNOUNCE → RECORD → TEARDOWN).

**Tasks:**
- [ ] `RtspHandler.kt` — RTSP server on TCP port 7000
  - Accept incoming connections
  - Parse RTSP request lines, headers, and body
  - Route to method handlers: OPTIONS, SETUP, ANNOUNCE, RECORD, TEARDOWN, GET_PARAMETER, SET_PARAMETER
  - Parse SDP body from ANNOUNCE
  - Extract video/audio codec info and encryption keys from SDP
  - Build and send correct RTSP responses
  - Handle connection close and cleanup
- [ ] Input validation for all RTSP fields (max size, format checks)
- [ ] `RtspHandlerTest.kt` — unit tests for all RTSP methods, SDP parsing, malformed input
- [ ] Integration: `AirPlayReceiver` starts `RtspHandler` alongside `MdnsService`

**Definition of Done:**
- Full RTSP handshake completes from macOS without errors.
- All RTSP unit tests pass including security/edge-case tests.
- SDP parsing correctly extracts codec and key information.

**Acceptance Criteria:** AC-3.1 through AC-3.8

---

## Phase 4 – Video Decoding & Display

**Milestone:** M4

**Goal:** macOS Screen Mirroring video decoded and displayed at ≥25fps, ≤100ms latency.

**Tasks:**
- [ ] `VideoDecoder.kt` — MediaCodec-based H.264 decoder
  - Configure MediaCodec with SPS/PPS from SDP
  - Create decoder surface (linked to `StreamingScreen`)
  - Accept NAL units, queue to MediaCodec input buffers
  - Handle codec lifecycle (start, stop, release)
- [ ] `StreamingScreen.kt` — SurfaceView for video output
  - Create Surface and pass to VideoDecoder
  - Handle aspect ratio (letterbox)
- [ ] RTP video packet parsing in `RtspHandler` (de-interleave from RTSP TCP stream)
- [ ] `MainActivity` — switch to `StreamingScreen` when stream starts, back to `WaitingScreen` when it ends
- [ ] `VideoDecoderTest.kt` — unit tests for MediaCodec init, NAL unit extraction

**Definition of Done:**
- Video displays full-screen on Google TV.
- Frame rate ≥ 25fps, latency ≤ 100ms measured.
- Hardware decoder confirmed via `dumpsys`.
- All unit tests pass.

**Acceptance Criteria:** AC-4.1 through AC-4.9

---

## Phase 5 – Audio Decoding & Playback

**Milestone:** M5

**Goal:** Audio plays in sync with video (≤40ms drift). No dropouts or crackling.

**Tasks:**
- [ ] `AudioPlayer.kt` — audio decryption and playback
  - AES-128-CTR decryption using Bouncy Castle
  - Parse RTP audio packets (UDP socket)
  - Feed raw AAC-ELD or ALAC frames to AudioTrack
  - NTP-based presentation timestamp handling for A/V sync
- [ ] RTP audio packet receiving (UDP) in `RtspHandler`
- [ ] Timing channel (UDP) for NTP sync
- [ ] `AudioPlayerTest.kt` — unit tests for decryption, frame extraction, sync logic

**Definition of Done:**
- Audio plays in sync with video (≤40ms drift).
- No dropouts or crackling during 5-minute test.
- All audio unit tests pass.

**Acceptance Criteria:** AC-5.1 through AC-5.7

---

## Phase 6 – Stability & Reconnect

**Milestone:** M6

**Goal:** 30-minute stable streaming. Automatic reconnect after disruption.

**Tasks:**
- [ ] Reconnect logic in `AirPlayReceiver`
  - Detect disconnection (TEARDOWN or socket close)
  - Restart mDNS advertising within 5 seconds
  - Clean up all resources (MediaCodec, AudioTrack, sockets)
- [ ] Memory leak audit: verify all resources released in `onDestroy`
- [ ] 30-minute automated stability test (manual + logcat monitoring)
- [ ] `AirPlayReceiverTest.kt` — unit tests for reconnect state machine

**Definition of Done:**
- 30-minute continuous stream passes without crash or disconnect.
- RAM stays ≤ 150 MB, CPU stays ≤ 30% throughout.
- Reconnect works after Wi-Fi toggle and after sender disconnect.

**Acceptance Criteria:** AC-6.1 through AC-6.7

---

## Phase 7 – Fire TV Validation

**Milestone:** M7

**Goal:** All milestones M1–M6 also pass on Fire TV.

**Tasks:**
- [ ] Test all features on Fire TV hardware (Fire TV Stick 4K + Fire TV Stick 3rd gen)
- [ ] Fix any Fire TV-specific issues (API level 25 compatibility, Fire OS quirks)
- [ ] Verify `firetv` flavor uses no API > level 25 without runtime checks
- [ ] Performance profiling on Fire TV (may have weaker SoC than Google TV)

**Definition of Done:**
- All AC-7.x criteria pass on Fire TV hardware.

**Acceptance Criteria:** AC-7.1 through AC-7.7

---

## Phase 8 – Release

**Milestone:** M8

**Goal:** Public v1.0 release with clean code, full docs, green CI, and APKs for both platforms.

**Tasks:**
- [ ] Complete `docs/ARCHITECTURE.md`
- [ ] Complete `docs/CONTRIBUTING.md`
- [ ] Complete `docs/TESTING.md`
- [ ] Finalize `README.md` with sideloading instructions
- [ ] Write `CHANGELOG.md` v1.0.0 entry
- [ ] Run full test suite and fix any remaining failures
- [ ] Build signed release APKs for both flavors
- [ ] Create GitHub Release with both APKs attached
- [ ] Tag `v1.0.0` in git

**Definition of Done:**
- All AC-8.x criteria pass.
- GitHub Release exists with both signed APKs.
- All CI workflows are green on the release tag.

**Acceptance Criteria:** AC-8.1 through AC-8.10

---

## Milestone Summary Table

| Milestone | Phase | Key Deliverable | Key Acceptance Criterion |
|---|---|---|---|
| M0 | 0 – Spec | 4 spec documents | All docs present and committed |
| M1 | 1 – Skeleton | Buildable app | Starts without crash on both platforms |
| M2 | 2 – Discovery | mDNS working | macOS sees device within 3s |
| M3 | 3 – Handshake | RTSP session | Handshake completes without errors |
| M4 | 4 – Video | H.264 streaming | ≥25fps, ≤100ms latency on Google TV |
| M5 | 5 – Audio | Audio streaming | A/V sync ≤40ms on Google TV |
| M6 | 6 – Stability | Reconnect + 30min test | 30min stable, reconnect working |
| M7 | 7 – Fire TV | Fire TV tested | All M1–M6 also pass on Fire TV |
| M8 | 8 – Release | v1.0 release | APKs built, CI green, docs complete |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| AirPlay 2 protocol has undocumented behaviors | High | High | Reference UxPlay and RPiPlay open-source implementations |
| Hardware H.264 decoder not available on some Fire TV devices | Medium | High | Test on actual hardware early; implement graceful fallback message |
| mDNS multicast blocked by router (AP isolation) | Medium | Medium | Document this in README; user must enable multicast on router |
| Audio/video sync more complex than expected | Medium | Medium | Implement NTP sync from day one; don't retrofit |
| API level 25 (Fire TV) lacks features needed | Low | Medium | Check Android API docs before using any API > 24; runtime checks |
| Open-source AirPlay implementations have legal uncertainty | Low | High | Use only for reference (protocol understanding); write all code from scratch |
