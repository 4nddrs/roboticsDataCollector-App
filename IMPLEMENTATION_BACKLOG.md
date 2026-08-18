# Wear and Work — Backlog of remaining work

**Sources:** PRD v1.0 (`Robotics_Egocentric_Data_Collection_App_PRD_v1.0_Detailed.pdf`) + Product Spec v2.0 (message).  
**Baseline:** Android app `RoboticsDataCollector` as of 2026-08-18.  
**Goal:** Finish a capture-first MVP that matches “wear and work”: one Start, hours of natural manipulation, reliable synchronized raw data, Guardian quality signals, local export. Semantic understanding stays offline.

Legend: **P0** must-have MVP · **P1** core Guardian / UX · **P2** useful extensions · **FUTURE** out of MVP (do not block V1).

---

## 0. Already implemented (do not rebuild)

Use this as the baseline so new work extends it instead of replacing it.

| Area | Current behavior |
|---|---|
| Capture | CameraX preview + continuous MP4 (`video.mp4`) + mic |
| IMU | Accel + gyro at `SENSOR_DELAY_FASTEST`, CSV with `elapsedRealtimeNanos` |
| Session object | `session_<epoch>/` with `video.mp4`, `imu_data.csv`, `metadata.json` |
| Pre-flight | Storage ≥ 2 GB, battery ≥ 20% or charging, IMU present, CAMERA + RECORD_AUDIO |
| Guardian (partial) | MediaPipe Hands (boolean visible), blur (Laplacian), under/over exposure |
| Events | Volume Up = MARK, Vol Down tap = SUCCESS, long/double = FAILURE, on-screen MARK, haptics |
| Reliability (partial) | Low battery &lt; 5% / shutdown flush; metadata `RECORDING` / `COMPLETED` / `INTERRUPTED_*` |
| Export (partial) | Session Manager: list, delete, share via FileProvider |
| UI | Full-screen preview, REC + timer, hands badge, quality pills, START/STOP |

**Still missing vs PRD/spec:** pause, 1080p/30 enforced, file segmentation, thermal, left/right/partial hands, workspace visibility, obstruction, frame-drop logs, experiment/participant metadata, mounting check, end-of-session quality report, dataset manifest, true recovery of `RECORDING` folders, iOS.

---

## 1. P0 — Capture, session, and reliability

### T-01 — Enforce 1080p @ 30 FPS (and record actuals)
- **PRD:** §9, §57 · **Spec:** §21, §57
- **Detail:** `LifecycleCameraController` does not pin Quality/FPS. Select `Quality.FHD` (fallback HD/SD), request 30 FPS, write **requested vs achieved** `fps`, `width`, `height`, `bitrate` into `metadata.json`.
- **Done when:** A session on a capable device is 1080×1920 or 1920×1080 @ ~30 FPS; metadata matches the file (`MediaExtractor` / MediaInfo).

### T-02 — Session-level PAUSE / RESUME
- **PRD:** §17 P2 (also listed P0 in spec §57) · **Spec:** §7, §19
- **Detail:** Pause **stops writing video+IMU+audio** without ending the session. Resume appends a new video segment (see T-03) and continues IMU. UI: tiny PAUSE (not a second Start). Volume keys ignored while paused. Metadata: `PAUSED` + pause intervals.
- **Done when:** One session folder can contain collect → pause → collect → stop; timestamps stay monotonic; status ends `COMPLETED`.

### T-03 — Automatic file segmentation (long sessions)
- **PRD:** §8, §9, §17 P0 · **Spec:** §21, §57
- **Detail:** Split video every **N minutes** (e.g. 5–15) or at size cap so a 1–4 h session is not one fragile MP4. Files: `video_000.mp4`, `video_001.mp4`, … plus a `segments.json` with `start_ns`, `end_ns`, `path`. IMU stays one CSV **or** is rotated with the same clock. **Never delete** earlier segments.
- **Done when:** A 20+ min test produces multiple playable MP4s; concatenating by `segments.json` reconstructs the session.

### T-04 — Timestamp & sync contract (all streams)
- **PRD:** §9, §10, §20 · **Spec:** §6, §61
- **Detail:** Document and store one clock: `SystemClock.elapsedRealtimeNanos()`. Record: video first-frame ns (CameraX), IMU rows, event markers, Guardian samples. Add `timebase` + `boot_elapsed_ns_at_start` in metadata. Align MARK/SUCCESS/FAILURE to video time (`t_rel_s`).
- **Done when:** A MARK at wall-clock T maps to the correct video second ±1 frame on a test clip.

### T-05 — True session recovery
- **PRD:** §11, §17 P0 · **Spec:** §57
- **Detail:** On launch, detect folders with `status=RECORDING` or missing finalize. Offer **Recover** (close IMU, mark `INTERRUPTED_SYSTEM`, keep playable segments) vs **Discard**. Do not leave orphan RECORDING sessions.
- **Done when:** Kill the app mid-session; reopen; session is recoverable and listed with interrupt status.

### T-06 — Thermal monitoring (pre-flight + Guardian)
- **PRD:** §5, §9, §17 P1 · **Spec:** §9, §30, §57
- **Detail:** Pre-flight: `PowerManager.currentThermalStatus` (or BatteryManager temp). During capture: sample every 10–30 s; log `thermal_status` in quality log (T-11). Escalate: subtle HUD → haptic if `SEVERE`. **Do not stop** unless the OS is about to kill (then T-05).
- **Done when:** Metadata/quality log contains thermal series; pre-flight fails or warns on `CRITICAL`.

### T-07 — Storage monitoring **during** capture (not only pre-flight)
- **PRD:** §5, §9 · **Spec:** §30, §35, §37
- **Detail:** Poll free space every 15–30 s. Warn at 1 GB; auto-stop like battery at **500 MB** with `INTERRUPTED_LOW_STORAGE`. HUD: Storage ✓ / 🟡 / 🔴.
- **Done when:** Simulated low space stops session cleanly with that status.

### T-08 — Dataset manifest (`manifest.json`)
- **PRD:** §12, §14, §57 · **Spec:** §45
- **Detail:** Per session, a machine-readable inventory: file names, MIME, bytes, checksum (SHA-256), time ranges, schema version. Session Manager share should include it.
- **Done when:** A workstation can ingest a folder using only `manifest.json` + files.

### T-09 — Experiment / participant / task metadata (Start Session)
- **PRD:** §13, §19 · **Spec:** §8, §40, §41
- **Detail:** Before START: Experiment, Participant ID (e.g. P012), Environment, Task/Activity, optional Skill, optional narration note. Persist in `metadata.json`. Session Manager shows these fields.
- **Done when:** Two participants in the same experiment are distinguishable in export without renaming folders.

### T-10 — App version + camera/sensor configuration in metadata
- **PRD:** §13
- **Detail:** `app_version`, `applicationId`, lens facing, AE/AF, IMU vendor/range if available, `recording_profile` (e.g. `fhd30_imu_fastest`).
- **Done when:** metadata is enough to reproduce capture settings.

---

## 2. P1 — Data Collection Guardian (manipulation-aware)

### T-11 — Persistent quality log (`quality.csv` or `quality.jsonl`)
- **PRD:** §12 · **Spec:** §30, §53
- **Detail:** Low-rate (~1–4 Hz) rows: `timestamp_ns`, hands state, scores, blur variance, mean luma, fps/drops, battery, storage, thermal. **Do not** rely only on end-of-session aggregates.
- **Done when:** A 1-min session has hundreds of quality rows aligned to IMU/video time.

### T-12 — Left / right / both / none / partial hand states
- **PRD:** §6 · **Spec:** §11–§13, §34, §57
- **Detail:** Use MediaPipe `handedness` (already available) instead of boolean `isHandVisible`. States: `BOTH`, `LEFT`, `RIGHT`, `NONE`, `PARTIAL` (landmarks clipped at image border, e.g. &gt;20% of 21 points within 3% of edge). HUD: 🟢 Both / 🟡 Right partial / 🔴 No hands.
- **Done when:** Covering one hand changes the badge; clipping near the frame edge → PARTIAL.

### T-13 — Hands-out-of-frame escalation (no nagging)
- **PRD:** §6, §7 · **Spec:** §13, §16, §34, §36
- **Detail:** State machine: short gap → **no UI**; persist ~2–3 s → tiny indicator; persist ~8 s → “Move camera down slightly — hands not visible for 8 s”; optional **one** haptic. Never spam. Recording continues.
- **Done when:** Waving hands out for 1 s is silent; 10 s shows the message once, not every frame.

### T-14 — Workspace visibility
- **PRD:** §5, §10 · **Spec:** §14, §15, §57
- **Detail:** Heuristic **without** full object detection: downward pitch (accel gravity vs camera), luma/texture in lower 50% of frame, not a uniform ceiling/sky. States: workspace GOOD / BAD. Combine with hands for Interaction Visibility (T-15).
- **Done when:** Camera at ceiling → workspace BAD; angled at a table with objects → GOOD on a lab test.

### T-15 — Interaction Visibility Score (internal GOOD / DEGRADED / POOR)
- **Spec:** §15, §54
- **Detail:** Combine hand visibility, workspace, blur, exposure, motion relevance (IMU or optical flow proxy). Store overall 0–1 and class. Participant sees only 🟢/🟡/🔴, not numbers.
- **Done when:** End report (T-21) includes coverage minutes: hands visible, workspace visible, good vs degraded interaction.

### T-16 — Camera obstruction
- **PRD:** §5 · **Spec:** §32, §58
- **Detail:** Distinct from underexposure: very low variance + dark **or** large saturated blob in center (finger). Message: “Camera partially obstructed.” Persist &gt;1 s like blur.
- **Done when:** Finger on lens triggers obstruction, not only “Low light.”

### T-17 — Frame health (dropped frames / FPS)
- **PRD:** §5 · **Spec:** §30, §53
- **Detail:** Compare CameraX timestamps vs expected 33.3 ms. Count drops; log in quality + session summary. HUD 🟡 if FPS &lt; 24 sustained.
- **Done when:** Summary shows `dropped_frames` and `achieved_fps`.

### T-18 — IMU / timestamp health during session
- **PRD:** §5 · **Spec:** §30
- **Detail:** Detect IMU gaps &gt; 20 ms at FASTEST (or &gt; 50 ms). Log `sensor_gaps`. If IMU dies, recording continues; Guardian 🔴 Sensors.
- **Done when:** Unplugging is N/A; simulated gap appears in quality log.

### T-19 — Guardian AI must not kill capture
- **PRD:** §10, §11, §18 · **Spec:** §28, §29
- **Detail:** Isolate MediaPipe on its executor; catch all errors; if inference OOMs or overheats, **disable Guardian AI**, keep RGB+IMU+audio, flag `guardian: degraded`.
- **Done when:** Force-fail HandLandmarker; video still finalizes.

### T-20 — Minimal persistent status strip during COLLECTING
- **PRD:** §7 · **Spec:** §17, §35
- **Detail:** Almost invisible: `● REC  Hands ✓  Cam ✓  Storage ✓`. Tap opens a detail sheet (optional). Remove bulky FAB chrome where possible; START/STOP only as session-level.
- **Done when:** Operator can use the phone without reading numbers; participant can ignore the screen.

### T-21 — End-of-session quality + interaction coverage report
- **PRD:** §20 · **Spec:** §53–§55
- **Detail:** After STOP: duration, video/audio/IMU saved ✓, hand visibility %, workspace %, camera quality %, dropped frames, sensor gaps, overall GOOD/DEGRADED/POOR, storage used GB. Buttons: **Done** (default), optional Review. Do not force clip review.
- **Done when:** Stop always shows this screen before returning to idle.

### T-22 — Operator timeline (Session Manager v2)
- **PRD:** §56 · **Spec:** §56
- **Detail:** Session detail: video scrubber, overlay of MARK/SUCCESS/FAILURE, hand-visibility bars, quality bars. Optional first version: stills + event list, not full waveform.
- **Done when:** Researcher opens a session and jumps to a MARK timestamp.

---

## 3. P1 — Mounting / pre-flight completeness

### T-23 — Extended pre-flight (thermal, timestamp, calibration placeholders)
- **PRD:** §7, §9 · **Spec:** §9
- **Detail:** Add checks: thermal, clock monotonic, optional “calibration file present.” Camera/mic already covered. Show the full checklist UI from spec §9.
- **Done when:** Pre-flight list matches spec items that are measurable on Android.

### T-24 — WAITING_FOR_WEAR / mounting check
- **Spec:** §7, §10, §19
- **Detail:** After pre-flight, **do not** start capture until: preview shows workspace + at least one hand, orientation “tilted down.” Copy: “Tilt phone slightly downward” / “Move camera lower — hands are not visible.” Button **START COLLECTION** only then. Then user mounts and works.
- **Done when:** A ceiling-pointing phone cannot start collection without a confirm override (logged).

### T-25 — Session state machine implemented as data, not ad-hoc flags
- **Spec:** §7
- **Detail:** Explicit states: `READY → PRE_FLIGHT → WAITING_FOR_WEAR → COLLECTING → PAUSED → FINALIZING → VALIDATING → COMPLETED` (+ interrupt). Drive UI from state. Persist last state for T-05.
- **Done when:** All transitions are logged; illegal transitions are impossible.

---

## 4. P2 — Capture UX and markers

### T-26 — Haptic vocabulary aligned with spec
- **Spec:** §36 · (partially done)
- **Detail:** Map: start = 1 short, stop = 2 short, critical = long, persistent no-hands = optional one pulse. Do not vibrate on every MARK unless configured.
- **Done when:** Spec table matches `HapticFeedbackManager` and is documented in-app settings.

### T-27 — Bluetooth physical marker (HID / custom)
- **PRD:** §15 · **Spec:** §43, §58
- **Detail:** Pair a BT button; map click to MARK / SUCCESS / FAILURE. Same `events[]` schema. Fallback: volume keys (already implemented).
- **Done when:** A BT shutter or Flic-style click writes MARK without touching the screen.

### T-28 — Optional session narration (pre-roll)
- **Spec:** §41, §58
- **Detail:** Optional 10–30 s audio note **before** research audio, stored as `narration.m4a`, **not** mixed into `video.mp4` research track (spec §38).
- **Done when:** Research WAV/AAC in the movie has no TTS/UI prompts.

### T-29 — UI feedback audio must not contaminate research audio
- **Spec:** §37, §38
- **Detail:** If TTS warnings exist, play on a non-camcorder stream or headphones; default **haptics only**. Setting: `audio_feedback = off`.
- **Done when:** A warning cannot be heard on the exported video soundtrack.

### T-30 — Recording profiles (battery vs quality)
- **Spec:** §21
- **Detail:** Profiles: `QUALITY` (1080p30), `ENDURANCE` (720p24, Guardian 1 Hz), `IMU_ONLY_FALLBACK`. User picks at start (T-09).
- **Done when:** 1 h endurance session completes on a mid-range phone without thermal kill (lab test).

### T-31 — Foreground service for long capture
- **PRD:** §8, §9 duration · **Spec:** §21
- **Detail:** `foregroundServiceType=camera|microphone` so Android 14/15 does not kill capture when the screen is off / activity paused. Notification: “Collecting — tap to return.” Pause/stop from notification optional.
- **Done when:** Screen off 10 min; video still continuous (or segmented) without OS kill.

---

## 5. Export, structure, iOS

### T-32 — Folder layout matching PRD dataset tree
- **PRD:** §12 · **Spec:** §45
- **Detail:** `experimentId/participantId/sessionId/{raw video(s), imu, metadata, quality, calibration/, manifest.json}`. Keep backward compatibility with current `session_*` at files root (migrate or dual-read in SessionRepository).
- **Done when:** Export zip/folder is ingestible by a documented schema version.

### T-33 — Calibration stub folder
- **PRD:** §12, §13 · **Spec:** §9, §58
- **Detail:** `calibration/camera.json` even if identity/intrinsics unknown (`status: uncalibrated`). Room for checkerboard later.
- **Done when:** Every new session has the file; processors do not crash if uncalibrated.

### T-34 — Bulk export / USB / folder copy instructions
- **PRD:** §14
- **Detail:** In Session Manager: “Export all” (zip or copy to user-selected tree via SAF). Document ADB path. Offline-first (already).
- **Done when:** Researcher copies a full experiment off-device without rebuilding metadata.

### T-35 — iOS companion (secondary platform)
- **Spec:** header, secondary platform
- **Detail:** Separate project; same session schema and Guardian semantics. Not required to close Android V1.
- **Done when:** Spec’d as a later program; schema version shared.

---

## 6. FUTURE — do not implement in V1 capture app

Keep architecture hooks only (metadata fields, quality log columns). **Do not** ship as capture blockers:

| ID | Item | Spec / PRD |
|---|---|---|
| F-01 | Automatic interaction / episode segmentation on device | §22–§23, §59 |
| F-02 | Hand events (APPROACH, GRASP, RELEASE, …) | §24 |
| F-03 | Object events / tracking / contact | §25–§26, §59 |
| F-04 | Authoritative 3D hand pose / 21-kpt as raw dataset | §27 (derived only) |
| F-05 | On-device object detection required for recording | §28 |
| F-06 | VLM annotation, skill labels | §59 |
| F-07 | SLAM / camera trajectory | §59 |
| F-08 | External / multi-cam sync (LED+beep) | §51–§52 |
| F-09 | Cloud upload / dataset platform | §59 |
| F-10 | Auto-pause on “phone on table” | §20 (explicitly not automatic in V1) |

**Invariant:** RAW session is never deleted after segmentation (spec §47).

---

## 7. Suggested implementation order (Android V1)

1. **T-25** state machine + **T-09** experiment/participant fields (unblocks metadata).  
2. **T-01** 1080p30 + **T-04** timebase + **T-03** segmentation (unblocks 1 h sessions).  
3. **T-31** foreground service + **T-05** recovery + **T-07** storage-during-capture + **T-06** thermal.  
4. **T-11** quality.jsonl + **T-12/T-13** hand states + escalation.  
5. **T-14/T-15** workspace + IVS; **T-16/T-17** obstruction + drops.  
6. **T-24** mounting check; **T-20** quiet HUD; **T-02** pause.  
7. **T-21** end report; **T-08** manifest; **T-32/T-33** folder schema.  
8. **T-22** operator timeline; **T-26/T-29** haptics/audio policy; **T-30** profiles.  
9. **T-27** BT marker when hardware exists.

---

## 8. V1 success criteria (from PRD §20)

A participant can mount the phone, press **Start once**, work naturally for **≥ 1 hour**, and produce a **complete synchronized** session (video segments + audio + IMU + timestamps + metadata + quality log) **without operating the UI during the task**, with Guardian coverage that distinguishes **video exists** vs **usable manipulation data**.

**KPIs to start measuring after T-11 + T-21:** session failure rate, hand-visible coverage %, usable-frame %, storage/thermal abort rate, participant interruptions per hour.
