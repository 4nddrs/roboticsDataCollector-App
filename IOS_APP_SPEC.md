# Wear and Work — iOS equivalent (Xcode)

**Purpose:** Build an iOS companion that produces the **same session dataset** as the Android app `RoboticsDataCollector`, so a lab can mix iPhone and Android folders in one pipeline.  
**Product:** head-mounted egocentric capture — mount phone → Start once → work naturally → Stop.  
**Sources:** Android V1 (this repo) + PRD v1.0 + Spec v2.0.  
**Target:** Xcode, Swift, iOS 17+ (prefer iOS 18 if you want Action Button / Control Center extras).  
**Invariant:** semantic understanding stays **offline**. Do not block V1 on VLM, SLAM, objects, or episode segmentation.

Parity means **same files, same JSON keys, same clocks, same event types**. “Better” means iOS-native reliability and sensors — not a different dataset format.

---

## 1. Product goal (identical)

A participant mounts an iPhone (chest / forehead / shoulder rig), taps **Start once**, works 1–4 hours, and the device writes a complete synchronized session:

- video segments + microphone
- IMU CSV
- timestamps on one clock
- Guardian quality log
- `metadata.json` + `manifest.json`

The participant should not operate the UI during the task.

**Success KPI (same as Android):** session failure rate, hand-visible coverage %, usable-frame %, storage/thermal abort rate, interruptions per hour.

---

## 2. Session schema (must match Android)

Do **not** invent a parallel iOS schema. A workstation should ingest a folder using only `manifest.json` + files, regardless of OS.

### 2.1 Folder layout

```
<Documents or App Group>/
  {experiment}/
    {participantId}/
      session_{unix_ms}/
        video_000.mp4
        video_001.mp4
        …
        imu_data.csv
        metadata.json
        quality.jsonl
        segments.json
        manifest.json
        calibration/
          camera.json
```

Sanitize `experiment` / `participantId` to `[A-Za-z0-9._-]`, max 48 chars (same as Android).

Store under **Application Support** or **Documents**. Enable **Files app** visibility (`UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`) so researchers copy sessions over USB / AirDrop without a custom exporter.

### 2.2 `imu_data.csv`

```
timestamp_ns,sensor_type,x,y,z
```

- `sensor_type`: `ACCEL` | `GYRO` (same strings)
- units: m/s² and rad/s
- `timestamp_ns`: **one shared monotonic clock** (see §4)

Optional extra columns **after** the required five, never instead of them:

```
timestamp_ns,sensor_type,x,y,z,attitude_qx,attitude_qy,attitude_qz,attitude_qw
```

Better-than-Android: also write `imu_device_motion.csv` with gravity-separated user acceleration + attitude, still keyed by `timestamp_ns`. Keep `imu_data.csv` as the canonical ingest file.

### 2.3 `segments.json`

```json
{
  "segments": [
    {
      "index": 0,
      "path": "video_000.mp4",
      "start_ns": 123,
      "end_ns": 456
    }
  ]
}
```

Never delete earlier segments. Split every **10 minutes** (or at a size cap, e.g. 3.5 GB) so a 1–4 h session is not one fragile MP4.

### 2.4 `metadata.json` — required keys

Copy Android field names. Add `platform` and `ios_version`; keep `android_version` **absent or null** on iOS (do not fake it).

| Key | iOS value |
|---|---|
| `schema_version` | `1` (add if Android still omits it; both should have it) |
| `platform` | `"ios"` |
| `session_id` | `session_<unix_ms>` |
| `device_model` | `utsname.machine` mapped to marketing name if you want, raw ok |
| `ios_version` | `UIDevice.current.systemVersion` |
| `app_version` | `CFBundleShortVersionString` |
| `application_id` | bundle id, e.g. `com.yourlab.wearandwork` |
| `start_timestamp_ns` / `end_timestamp_ns` | same clock as IMU |
| `duration_seconds` | derived |
| `timebase` | `"mach_absolute_time_ns"` or `"elapsedRealtimeNanos"` **plus** document the mapping in §4 |
| `boot_elapsed_ns_at_start` | clock reading at session start |
| `video_file` | first segment name |
| `video_files` | array of all `video_NNN.mp4` |
| `imu_file` | `imu_data.csv` |
| `target_fps` / `requested_fps` | `30` |
| `resolution` | `"1080p"` |
| `achieved_width` / `achieved_height` | from the finished file |
| `achieved_fps` | measured (iOS should do this; Android often leaves it sparse) |
| `recording_profile` | `fhd30_imu_fastest` or `hd24_guardian_1hz` |
| `status` | see §2.6 |
| `sensor_gaps` | IMU gaps > 50 ms |
| `thermal_status_at_end` | ProcessInfo thermal state int |
| `pause_intervals_ns` | `[{start_ns, end_ns}]` |
| `events` | `[{timestamp_ns, event_type}]` with `MARK` / `SUCCESS` / `FAILURE` |
| `session_config` | experiment, participant_id, environment, task, skill, narration, profile |
| `guardian_summary` | same nested keys as Android |
| `pre_flight_status` | same nested keys, plus iOS extras allowed |
| `mount_check_overridden` | bool |

`guardian_summary` keys to keep:

- `hands_detected_percentage`, `total_analyzed_frames`, `hand_detected_frames`
- `blurred_frames_percentage`, `underexposed_frames_percentage`, `overexposed_frames_percentage`
- `workspace_visible_percentage`, `obstructed_frames_percentage`
- `dropped_frames`, `guardian_degraded`
- `detector` — e.g. `vision_hand_pose` or `mediapipe_hands`
- `model_asset` — model name or `"Vision.VNDetectHumanHandPoseRequest"`

### 2.5 `quality.jsonl`

One JSON object per line, ~1–4 Hz, same clock:

```json
{
  "timestamp_ns": 0,
  "hands": "BOTH",
  "workspace_visible": true,
  "obstructed": false,
  "laplacian_variance": 0,
  "mean_luminance": 0,
  "blurred": false,
  "underexposed": false,
  "overexposed": false,
  "ivs": "GOOD",
  "dropped_frames_total": 0,
  "guardian_degraded": false,
  "thermal_status": 0,
  "free_bytes": 0,
  "sensor_gaps": 0,
  "battery_percent": 80
}
```

`hands`: `BOTH` | `LEFT` | `RIGHT` | `NONE` | `PARTIAL`  
`ivs`: `GOOD` | `DEGRADED` | `POOR`

### 2.6 Status strings (exact)

```
RECORDING
PAUSED
COMPLETED
INTERRUPTED_LOW_BATTERY
INTERRUPTED_LOW_STORAGE
INTERRUPTED_SYSTEM
ERROR
```

### 2.7 `manifest.json`

```json
{
  "schema_version": 1,
  "session_id": "session_…",
  "written_at_ns": 0,
  "files": [
    { "name": "video_000.mp4", "relative_path": "video_000.mp4", "bytes": 0, "sha256": "…" }
  ]
}
```

SHA-256 every file except the manifest itself. Include `calibration/camera.json`.

### 2.8 `calibration/camera.json`

Always write, even if uncalibrated:

```json
{
  "status": "uncalibrated",
  "model": "iPhone16,2",
  "note": "Intrinsics not captured in V1"
}
```

**Better than Android (recommended P1):** if `AVCaptureDevice` format has `isCameraIntrinsicMatrixDeliveryEnabled`, save 3×3 intrinsics + lens distortion lookup into this file. Processors must still tolerate `uncalibrated`.

---

## 3. UX state machine (same screens)

Drive UI from data, not ad-hoc flags:

`SETUP → PRE_FLIGHT → WAITING_FOR_WEAR → COLLECTING → PAUSED → FINALIZING → REPORT`

Illegal transitions must be impossible.

| State | Screen | iOS notes |
|---|---|---|
| SETUP | Experiment, Participant ID, Environment, Task, Skill, narration note, profile | SwiftUI `Form`. Dark text on dark fields — force light text. |
| PRE_FLIGHT | Checklist; Continue only if all pass | Re-check on `scenePhase == .active` |
| WAITING_FOR_WEAR | Preview + “Tilt phone slightly downward” / “Move camera lower — hands are not visible” | **START COLLECTION** enabled when workspace + ≥1 hand. **Start anyway** logs `mount_check_overridden` |
| COLLECTING | Almost invisible HUD: `● REC  Hands ✓  Cam ✓  Storage ✓` | Tiny **PAUSE** + **STOP**. Volume / hardware markers work. Keep screen awake (`isIdleTimerDisabled = true`) |
| PAUSED | Timer frozen, keys ignored | Resume starts `video_{n+1}.mp4` |
| REPORT | Duration, files saved, coverage %, drops, gaps, GOOD/DEGRADED/POOR | **Done** default. Do not force clip review |

On launch: if any folder has `status=RECORDING` or `PAUSED`, show **Recover** (mark `INTERRUPTED_SYSTEM`, keep files) vs **Discard**.

Session list: duration, status, experiment/participant/task, % hands, size, Share (share sheet / Files), Delete.

Language: **English UI** (match Android), unless you add a later localization pass.

---

## 4. Timebase & sync (critical)

Android uses `SystemClock.elapsedRealtimeNanos()`. iOS has no identical API.

**Contract for iOS V1:**

1. Pick **one** clock for IMU, video, events, quality: `clock_gettime_nsec_np(CLOCK_UPTIME_RAW)` (or `mach_absolute_time` converted to ns). This is uptime excluding sleep — closest analog to elapsed realtime.
2. Write `timebase: "uptime_raw_ns"` in metadata.
3. Also write:
   - `boot_elapsed_ns_at_start`
   - `unix_epoch_ms_at_start` (`Date().timeIntervalSince1970`) so labs can align logs
4. Video: stamp each sample / first frame with the **same** ns clock. `CMSampleBuffer` has `presentationTimeStamp` on the capture session clock — convert to uptime_raw via a captured offset at session start:

   `offset = uptime_raw_ns - CMClockConvertHostTimeToSystemUnits(CMClockGetTime(CMClockGetHostTimeClock()))`  
   Document the conversion in a `TIMEBASE.md` inside the iOS repo.

5. MARK at wall time T must map to the correct video second **±1 frame**.

**Better:** log `video_first_frame_ns` and per-segment `start_ns` from the actual first encoded frame, not from “tap Start”.

---

## 5. Xcode project

### 5.1 New project

- **App**, Swift, **SwiftUI**, Interface SwiftUI, Language Swift
- Bundle ID: `com.<lab>.wearandwork`
- Team + signing (developer account required for camera + background)
- iPhone only (`TARGETED_DEVICE_FAMILY = 1`)
- Orientation: all (head mount is not locked portrait)
- Minimum iOS 17

Suggested modules (folders, not necessarily SPM packages):

```
WearAndWork/
  App/
    WearAndWorkApp.swift
    AppSession.swift          // state machine
  Capture/
    CameraService.swift       // AVCaptureSession
    VideoSegmentWriter.swift
    AudioPolicy.swift
  Sensors/
    IMUService.swift          // CoreMotion
  Guardian/
    FrameQualityAnalyzer.swift
    HandVisibility.swift      // Vision
    QualityLogger.swift
  Session/
    SessionStore.swift
    MetadataWriter.swift
    ManifestWriter.swift
    Recovery.swift
  UI/
    SetupView.swift
    PreFlightView.swift
    MountingView.swift
    CollectingView.swift
    ReportView.swift
    SessionListView.swift
```

### 5.2 Info.plist / target capabilities

Privacy strings (required or the app is killed at runtime):

- `NSCameraUsageDescription`
- `NSMicrophoneUsageDescription`
- `NSMotionUsageDescription` (if you query activity / some CM APIs; accel/gyro usually do not need it, still add a clear string)

Background modes (Signing & Capabilities → Background Modes):

- **Audio** (required for long capture with screen off — iOS will suspend camera-only apps)
- Optional: **Background processing** only if you finalize SHA-256 after stop

Also:

- `UIBackgroundModes` = `audio`
- `UIFileSharingEnabled` = YES
- `LSSupportsOpeningDocumentsInPlace` = YES
- `UIRequiresPersistentWiFi` = NO (offline-first)
- Prevent backup of session blobs: `URLResourceKey.isExcludedFromBackupKey` on the session root (sessions are large)

Audio session:

```
AVAudioSession.sharedInstance()
  .setCategory(.playAndRecord, mode: .videoRecording, options: [.allowBluetoothHFP, .mixWithOthers])
```

**Research audio must not contain UI TTS.** Haptics only (`UINotificationFeedbackGenerator` / `CHHapticEngine`). If you ever speak warnings, use a route that is **not** the camcorder track.

### 5.3 Entitlements you do **not** need for V1

- Push, iCloud (optional later for sync — PRD is offline-first)
- HealthKit
- DriverKit

---

## 6. Capture stack (equivalent + better)

### 6.1 Camera + video

| Android | iOS |
|---|---|
| CameraX `LifecycleCameraController` | `AVCaptureSession` + `AVCaptureMovieFileOutput` **or** `AVAssetWriter` |
| Quality FHD 30, fallback HD | `AVCaptureDevice.Format` where `1920×1080` (or 1080×1920) @ 30 fps, `activeColorSpace` sRGB / P3 recorded in metadata |
| Mic in MP4 | `AVCaptureDevice.default(for: .audio)` added to session |

**Prefer `AVAssetWriter`** over `MovieFileOutput` for long sessions: you control fragment interval, can rotate files without tearing the session, and can stamp `CMTime`.

Requirements:

- Back wide camera (`builtInWideAngleCamera`), not ultra-wide (distortion) unless the protocol says so
- Lock frame rate: `activeVideoMinFrameDuration = activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)`
- Write **requested vs achieved** fps / size / bitrate into metadata (probe with `AVAsset` after finalize)
- Video codec: **H.264** High Profile for max workstation compatibility (HEVC is smaller but worse for some ingest tools). Offer HEVC only in Endurance profile
- Bitrate target ~12–16 Mbps for 1080p30 Quality
- Stabilization: **off** for robotics (optical/EIS warps geometry). Record `stabilization: "off"`
- Auto exposure / AF: leave on, log `videoHDR`, `isLowLightBoostEnabled`

Preview: `AVCaptureVideoPreviewLayer` in a `UIViewRepresentable`.

### 6.2 Segmentation & pause

- Rotate file every 10 min: finish writer → start `video_{n+1}.mp4` without stopping preview
- Pause: stop writer + pause IMU; do not end session; status `PAUSED`
- Resume: new segment, IMU continues, timestamps monotonic
- Hardware markers ignored while paused

### 6.3 Background / screen off

This is where iOS is **harder** than Android’s FGS.

To survive Lock / screen off 10+ minutes:

1. `audio` background mode
2. Keep `AVCaptureSession` running
3. Silent audio is **not** enough if you already record mic — the recording session itself should keep you alive
4. Still start a **now-playing / recording** appearance so the user sees “Collecting — tap to return” (Live Activity on iOS 16.1+ is **better than Android FGS chrome**)

Live Activity (optional but better):

- Dynamic Island: `REC 12:04` + Hands ✓
- Deep link back into Collecting

If iOS kills the app anyway: recovery dialog on next launch (T-05).

### 6.4 Thermal & battery

| Check | iOS API |
|---|---|
| Thermal | `ProcessInfo.processInfo.thermalState` — `nominal/fair/serious/critical`. Map to ints in quality log. Pre-flight **fail or warn** on `critical`. Haptic on `serious`. Do not stop unless OS is about to jetsam |
| Battery | `UIDevice.current.isBatteryMonitoringEnabled`; `batteryLevel`, `batteryState`. Pre-flight: ≥20% or charging. Auto-stop &lt;5% → `INTERRUPTED_LOW_BATTERY` |
| Storage | `URL.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])`. Warn 1 GB, stop 500 MB → `INTERRUPTED_LOW_STORAGE` |

Subscribe to `ProcessInfo.thermalStateDidChangeNotification` and `NSNotification.Name.NSProcessInfoPowerStateDidChange`.

### 6.5 IMU (CoreMotion)

```
CMMotionManager
  accelerometerUpdateInterval = 1/200   // or 1/100 if device capped
  gyroUpdateInterval = 1/200
  startAccelerometerUpdates(to: imuQueue)
  startGyroUpdates(to: imuQueue)
```

**Better:** `startDeviceMotionUpdates(using: .xArbitraryZVertical)` at 100–200 Hz and derive accel/gyro **plus** attitude. Still emit the 5-column `imu_data.csv`.

- Flush CSV every 100 samples
- Count gaps > 50 ms → `sensor_gaps`
- If IMU dies, **keep video**; Guardian shows Sensors 🔴
- Pause: stop writing rows (keep manager running or pause both — pick one and document)

### 6.6 Profiles

| Profile id | Video | Guardian |
|---|---|---|
| `fhd30_imu_fastest` | 1080p30 H.264 | Vision ~4 Hz |
| `hd24_guardian_1hz` | 720p24 (or 720p30) HEVC | Vision 1 Hz |

User picks on Setup (same as Android).

---

## 7. Data Collection Guardian (iOS)

Guardian must **never kill capture**. Isolate Vision on a serial queue; on OOM / error set `guardian_degraded` and keep RGB+IMU+audio.

### 7.1 Hands — preferred path (better than MediaPipe-on-Android for maintenance)

Use **Vision** `VNDetectHumanHandPoseRequest`:

- `maximumHandCount = 2`
- Chirality: `.left` / `.right` from `VNHumanHandPoseObservation.chirality` (iOS 15+)
- States:
  - `BOTH`, `LEFT`, `RIGHT`, `NONE`
  - `PARTIAL` if ≥5 of 21 joints (or ≥20% of returned joints) lie within 3% of the image border
- Confidence gate (e.g. wrist + index tip > 0.5) to reject ghosts

Fallback: MediaPipe Hands via the official iOS `MediaPipeTasksVision` pod/SPM — only if Vision chirality is too weak on your mount. Record `detector` accordingly.

Do **not** require 21-kpt as the raw dataset (PRD: derived only).

### 7.2 Frame quality (same heuristics)

On a downscaled luminance plane (from `AVCaptureVideoDataOutput` kCVPixelFormatType_420YpCbCr8BiPlanar):

- Sharpness: Laplacian variance; warn if low for ≥1 s
- Exposure: mean luma; under &lt;40, over &gt;220 (8-bit)
- Workspace: lower 50% of frame has mean 35–210 **and** std ≥ 12
- Obstruction: very low variance + dark (or saturated center blob) ≥1 s → “Camera partially obstructed.”

HUD copy (English, same as Android):

- Too much motion
- Low light / Too much light
- Move camera down slightly — hands not visible
- Workspace not visible — tilt camera down
- Camera partially obstructed

Hands-out escalation: 1 s silent → 2–3 s tiny indicator → **8 s** message + **one** haptic. Never spam. Recording continues.

### 7.3 Interaction Visibility Score (internal)

Combine hands + workspace + blur + exposure:

- `GOOD` — BOTH, workspace, no blur/exposure/obstruction
- `DEGRADED` — one hand or mild defects
- `POOR` — NONE, obstructed, or no workspace

Participant sees only 🟢/🟡/🔴, never the 0–1 number. End report includes coverage minutes / %.

### 7.4 Frame health

Compare `CMSampleBuffer` PTS deltas vs 33.3 ms. Count drops. HUD 🟡 if FPS &lt; 24 sustained. Summary: `dropped_frames`, `achieved_fps`.

---

## 8. Markers & haptics

Android volume keys are awkward on iOS (the system volume HUD steals them). To be **equivalent or better**, implement **all** of these, in order of reliability:

| Priority | Input | Mapping |
|---|---|---|
| P0 | On-screen **[ MARK ]** | MARK |
| P0 | Volume Up / Down via `MPRemoteCommandCenter` **and/or** `AVAudioSession` notification — test on device; may be rejected if it fights system volume | Up = MARK; Down tap = SUCCESS; long/double Down = FAILURE |
| P0 | **Action Button** (iPhone 15 Pro+) / Back Tap (Settings → Accessibility) documented as MARK | MARK |
| P1 | Bluetooth HID shutter / Flic / camera remote | same `events[]` |
| P1 | **Apple Watch** companion: three buttons MARK / SUCCESS / FAILURE (this is strictly better than Android volume keys for a head mount) |
| P2 | AirPods stem click as MARK | optional |

Haptic vocabulary (match spec):

- Start: 1 short
- Stop: 2 short
- Critical (battery / storage / thermal serious): long
- Persistent no-hands: **one** pulse
- FAILURE: double heavy
- Do not vibrate on every MARK unless a setting says so

Use `UIImpactFeedbackGenerator` + `UINotificationFeedbackGenerator`. Core Haptics if you want Watch mirroring later.

---

## 9. Pre-flight checklist (measurable on iOS)

Must pass before COLLECTING:

1. Camera authorized `.authorized`
2. Microphone authorized
3. Free space ≥ 2 GB
4. Battery ≥ 20% **or** charging / full
5. Accel + gyro available (`isAccelerometerAvailable && isGyroAvailable`)
6. Thermal not `critical`
7. Clock monotonic (always true; still log `timestamp_ok`)
8. Capture session can start (try a dry `startRunning()`)
9. Calibration file placeholder will be written (always)

Show the list with 🟢/🔴 like Android. Re-check button.

---

## 10. Session Manager & export (better than Android)

Android: FileProvider share of files.  
iOS should do that **and**:

1. **Share sheet** (`UIActivityViewController`) with all session files
2. **Open in Files** / “Save to Files”
3. **AirDrop** folder
4. **USB Finder** via File Sharing (already enabled)
5. Optional P2: zip the session with `ZIPFoundation` then share one archive (easier for Slack)
6. List shows experiment / participant / task (same as Android)

Do not upload to cloud in V1.

---

## 11. Architecture sketch (Swift)

```text
WearAndWorkApp
  └ AppModel (Observable)
       ├ SessionState
       ├ PreFlightService
       ├ CameraService (AVCaptureSession)
       ├ IMUService (CMMotionManager)
       ├ GuardianService (Vision + quality)
       ├ EventLog
       ├ DiskSessionStore
       └ RecoveryService
```

Rules:

- Camera + IMU + Guardian on **separate** queues
- Guardian errors cannot cancel `AVAssetWriter`
- Atomic metadata write: write `metadata.json.tmp` then `replaceItemAt`
- On `scenePhase` background: keep session; on memory warning: drop Guardian first
- `applicationWillTerminate` / `didReceiveMemoryWarning`: flush IMU, finish current MP4, status `INTERRUPTED_SYSTEM` if still RECORDING

---

## 12. Suggested Xcode implementation order

Mirror Android V1 order:

1. Session state machine + Setup fields + folder layout  
2. AVCapture 1080p30 + timebase + 10 min segments  
3. Background audio + recovery + storage/thermal during capture  
4. `quality.jsonl` + Vision hands L/R/PARTIAL + 8 s escalation  
5. Workspace + IVS + obstruction + drops  
6. Mounting check + quiet HUD + pause  
7. End report + manifest + calibration stub  
8. Session list + share + Files  
9. Watch / Action Button / BT marker  
10. Camera intrinsics in `calibration/camera.json`

Do **not** implement in V1 capture: on-device episodes, grasp events, object tracking, VLM, SLAM, multi-cam LED sync, cloud.

---

## 13. Where iOS should be **better** than the current Android app

These are worth doing in iOS V1 if time allows — they do not change the ingest schema.

1. **Live Activity / Dynamic Island** for REC + hands (clearer than a static FGS notification).  
2. **Apple Watch remote** for MARK/SUCCESS/FAILURE (volume keys are poor on a helmet).  
3. **DeviceMotion + attitude** extra CSV.  
4. **Real camera intrinsics** when the format supports it.  
5. **Achieved fps / bitrate** always filled (probe `AVAsset`).  
6. **Files app + AirDrop + zip** as first-class export.  
7. **LiDAR / depth** (Pro models only): optional `depth/` folder with a low-rate `.bin` or HEVC Alpha — **off by default**, flag in metadata `depth_recorded: false`. Do not require Pro hardware.  
8. **HEVC Endurance** for 2–4 h on thermal-limited phones.  
9. **Stabilization explicitly off** and logged (Android CameraX may still apply vendor EIS).  
10. **Lock white balance** after pre-flight if the protocol wants photometric consistency.

---

## 14. iOS-specific risks (plan for them)

| Risk | Mitigation |
|---|---|
| App suspended when locked | `audio` background mode + keep capture session; Live Activity |
| Thermal throttle drops fps | Endurance profile; log achieved_fps; do not lie in metadata |
| Vision too heavy on A-series | Guardian 1–4 Hz, not every frame; disable on thermal `serious` |
| Volume buttons change ringer volume | Watch / Action Button as primary; document “silent switch does not stop recording” |
| App Store review | If you stay TestFlight / enterprise / research, you avoid App Store. If you ship public: volume-key hijack is a review risk — prefer Watch |
| Large files in iCloud backup | exclude session directory from backup |
| Photo Library pollution | **Do not** save to Camera Roll; sessions stay in the app container |

---

## 15. Test plan (close V1)

- [ ] 1080×1920 or 1920×1080 @ ~30 fps; metadata matches `AVAsset`  
- [ ] MARK maps to correct video second ±1 frame  
- [ ] Collect → pause → collect → stop; status `COMPLETED`; two+ MP4s  
- [ ] 20+ min test produces multiple playable MP4s; `segments.json` concatenates  
- [ ] Kill app mid-session; relaunch Recover keeps playable files  
- [ ] Free space 500 MB stop → `INTERRUPTED_LOW_STORAGE`  
- [ ] Battery &lt;5% → `INTERRUPTED_LOW_BATTERY`  
- [ ] Screen off 10 min: video still continuous or cleanly segmented  
- [ ] Cover one hand → badge LEFT/RIGHT; clip at edge → PARTIAL  
- [ ] Hands out 1 s silent; 10 s shows message once  
- [ ] Finger on lens → obstruction, not only “Low light”  
- [ ] Two participants same experiment distinguishable in export  
- [ ] `manifest.json` SHA-256 verifies  
- [ ] Android and iOS folders ingest with the **same** Python/script  

---

## 16. V1 done when

An iPhone can be mounted, **Start** pressed once, natural work for **≥ 1 hour**, and the folder is a complete synchronized session **byte-compatible** with Android V1 (`schema_version` 1), with Guardian coverage that distinguishes “video exists” vs “usable manipulation data”.

After that, share `schema_version` with the Android app and bump together — never fork keys.
