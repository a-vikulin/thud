# ⚠️ NEVER COMMIT WITHOUT USER CONSENT!!! ⚠️

## Agent Behavioral Rules

**Role:** Senior engineer; the human is the architect. Move fast, but never faster than they can verify.

**Critical behaviors:**
- Surface assumptions explicitly before implementing (`ASSUMPTIONS I'M MAKING: 1. ... 2. ...`)
- STOP on inconsistencies/confusion — name it, ask, wait for resolution
- Push back on bad ideas (sycophancy is a failure mode)
- Prefer boring, obvious solutions — if 100 lines suffice, don't write 1000
- Touch only what you're asked to touch — no unsolicited cleanup/refactoring
- After refactors, list dead code and ask before removing

**Patterns:**
- Reframe imperative instructions as success criteria
- Test-first for non-trivial logic; naive-then-optimize for algorithms
- Emit lightweight `PLAN:` before multi-step tasks

**Output standards:**
- No premature abstractions, no clever tricks without comments
- Quantify impacts ("~200ms latency" not "might be slower")
- After changes, summarize: `CHANGES MADE:`, `THINGS I DIDN'T TOUCH:`, `POTENTIAL CONCERNS:`

---

# tHUD Project Guidelines

> **Note:** If `CLAUDE-PRIVATE.md` exists, read it for maintainer-specific instructions.

## Critical Rules

### Android Resource Externalization
**IMPORTANT**: Never use inline styling in Kotlin code. Use `R.dimen.*`, `R.string.*`, `R.color.*` instead of hardcoded values.

### SharedPreferences Name
**CRITICAL**: Always use `"TreadmillHUD"` as the SharedPreferences name.

### Single Source of Truth
**CRITICAL**: Before adding new data flows, check if a pathway already exists. Never send the same data through multiple callbacks.

| Data | Source | Flows To |
|------|--------|----------|
| Treadmill telemetry | `TelemetryManager` | `HUDService` → managers |
| HR data for adjustments | `onHeartRateUpdate()` | `WorkoutExecutionEngine.onHeartRateUpdate()` only |
| Speed/incline commands | `TelemetryManager.setTreadmillSpeed/Incline()` | THE ONLY PATH to GlassOsClient |
| Elapsed time | Treadmill via `onElapsedTimeUpdate()` | `WorkoutExecutionEngine.treadmillElapsedSeconds` |
| Workout state | `WorkoutExecutionEngine.state` | Collected by `WorkoutEngineManager` |
| Adjustment coefficients | `WorkoutExecutionEngine` | Chart via `WorkoutEngineManager` |
| Saved BT devices | `SavedBluetoothDevices` | Sensor managers, BT dialog |
| Run data for export | `WorkoutRecorder` | `createRunSnapshot()` → `FitFileExporter` |
| Execution steps for export | `WorkoutExecutionEngine` | `createRunSnapshot()` → `FitFileExporter` |
| GlassOS connection state | `TelemetryManager.hasEverConnected` | `isReconnecting` flag |
| FIT device identification | `ServiceStateHolder` (SharedPrefs) | `UserExportSettings` → `FitFileExporter` |
| Treadmill name | `GlassOsClient.treadmillName` | `ServiceStateHolder` → FTMS device names |
| FTMS server settings | `ServiceStateHolder` (SharedPrefs) | `HUDService` → server start/stop |
| System workouts | `WorkoutRepository` (DB, `systemWorkoutType`) | Engine stitching, editor sentinels |
| Phase boundaries | `WorkoutExecutionEngine` (phase step counts) | Chart, panel, coefficient reset |
| Per-step coefficients | `WorkoutExecutionEngine` (`stepCoefficients` map) | Chart via `WorkoutEngineManager` (ONE_STEP mode only) |
| Garmin auto-upload flag | `ServiceStateHolder` (SharedPrefs) | `HUDService` → export flow |
| Garmin OAuth tokens | `GarminConnectUploader` (EncryptedSharedPrefs) | Upload flow only |
| FIT bytes for upload | `FitFileExporter.FitExportResult` | `HUDService` → `GarminConnectUploader` |
| Remote control config | `RemoteControlManager` (SharedPrefs JSON) | `RemoteControlBridge` → AccessibilityService |
| Remote key events (tHUD) | `RemoteControlAccessibilityService` | `RemoteControlBridge` → `RemoteControlManager` |
| Remote key events (Android) | `RemoteControlAccessibilityService` | Executed directly (AudioManager / performGlobalAction) |
| Panel save/restore | `HUDService` static helpers | Any full-screen activity via `notifyActivity{Foreground,Background,Closed}` |

### ⚠️ SPEED - ABSOLUTE RULES ⚠️

**SETTING speed:** NEVER call `glassOsClient.setSpeed()` directly! ALL speed commands go through `TelemetryManager.setTreadmillSpeed(adjustedKph)` which divides by `paceCoefficient` to get raw treadmill speed.

**READING speed:** NEVER use raw treadmill speed for internal logic! Always convert: `adjustedSpeed = state.currentSpeedKph * state.paceCoefficient`. All internal components work with adjusted speed.

**Flow OUT (setting):**
```
Engine event → WorkoutEngineManager → listener.onSetTreadmillSpeed(adjustedKph)
→ TelemetryManager.setTreadmillSpeed() ← COEFFICIENT APPLIED → glassOsClient.setSpeed(rawKph)
```

**Flow IN (reading):**
```
TelemetryManager receives rawSpeed → state.currentSpeedKph = rawSpeed
→ HUDService: adjustedSpeed = raw * paceCoefficient → WorkoutEngineManager/Engine
```

**Raw speed used ONLY for:** HUD pace box lower text `(X.X kph)` display, and `TelemetryManager.setTreadmillSpeed()` conversion.

### ⚠️ INCLINE - ABSOLUTE RULE ⚠️
**ALL incline throughout the app uses EFFECTIVE incline (outdoor equivalent)!**

- Effective = treadmill incline - adjustment (default adjustment = 1.0)
- 1% treadmill ≈ flat outdoor (no air resistance)

**Setting:** `TelemetryManager.setTreadmillIncline(effectivePercent)` adds `state.inclineAdjustment` before sending to GlassOS.
**Reading:** `TelemetryManager.onInclineUpdate(treadmillPercent)` subtracts `state.inclineAdjustment` before storing in `state.currentInclinePercent`.

All stored/displayed values (`state.currentInclinePercent`, popup menu, min/max, editor, StrydManager`) are effective incline.

### ⚠️ FIT EXPORT - CRITICAL RULES ⚠️
**ALWAYS use the RunSnapshot pattern!** Export is async — snapshot captures data immutably before cleanup.

```kotlin
// CORRECT
val snapshot = createRunSnapshot(workoutName)
workoutEngineManager.resetWorkout()  // Safe - data captured
if (snapshot != null) exportWorkoutToFit(snapshot)

// WRONG - race condition!
exportWorkoutToFit(workoutName)      // Async
workoutEngineManager.resetWorkout()  // Clears data before export runs!
```

`workoutDataExported` flag prevents duplicates — set BEFORE async export, reset only in `resetRunState()`.

### ⚠️ RUN LIFECYCLE - CRITICAL INVARIANT ⚠️
**No run is ever discarded without saving to FIT!**

| Method | Purpose | When to Use |
|--------|---------|-------------|
| `resetRunState()` | Internal reset (no export) | Only after data is exported/captured |
| `saveAndClearRun(name)` | Export then reset | Safe way to clear with export |
| `createRunSnapshot(name)` | Capture data immutably | Before any cleanup |
| `exportWorkoutToFit(snapshot)` | Export from snapshot | After snapshot captured |

### GlassOS Reconnection
Run data preserved in memory during reconnection. Key: `TelemetryManager.hasEverConnected` distinguishes initial vs reconnection. On reconnection, `state.isReconnecting` is set → post-reconnection IDLE from GlassOS is **ignored** → run state preserved. Persisted run check is skipped (data is in memory).

---

## Key Utilities - USE THESE, DON'T REIMPLEMENT!

### PaceConverter (`util/PaceConverter.kt`)
Speed↔pace: `speedToPaceSeconds(kph)`, `paceSecondsToSpeed(seconds)`
Formatting: `formatPace(seconds)` → "M:SS", `formatPaceFromSpeed(kph)`, `formatDuration(seconds)` → "H:MM:SS", `formatDistance(meters)` → "X.XX km"
Cross-calc: `calculateDistanceMeters(seconds, kph)`, `calculateDurationSeconds(meters, kph)`

### HeartRateZones (`util/HeartRateZones.kt`)
`getZone(bpm, lthrBpm, z1Pct, z2Pct, z3Pct, z4Pct)` → zone 1-5
`getZoneColorResId(zone)`, `getStepTypeColorResId(type)` → color resources
`percentToBpm(percent, lthrBpm)` → conversion

### PowerZones (`util/PowerZones.kt`)
Same pattern as HeartRateZones but with `ftpWatts`: `getZone()`, `getZoneColorResId()`, `percentToWatts()`

### FileExportHelper (`util/FileExportHelper.kt`)
Exports to Downloads/tHUD via MediaStore. `saveToDownloads(context, sourceFile, filename, mimeType, subfolder)`, `getTempFile(context, filename)`. Subfolders: `ROOT` (tHUD/), `SCREENSHOTS` (tHUD/screenshots/).

### SavedBluetoothDevices (`service/SavedBluetoothDevices.kt`)
Unified BT sensor storage. `getAll/getByType/save/remove/isSaved/getSavedMacs`. Types: `HR_SENSOR`, `FOOT_POD`.

### SettingsManager (`service/SettingsManager.kt`)
All SharedPreferences keys as constants. Key groups: `pace_coefficient` (calibration), `hr_zone*_max` (HR zones), `threshold_pace_kph`, `default_incline`, treadmill min/max ranges (from GlassOS), `fit_*` (FIT Export device ID), `ftms_*` (FTMS server settings), `garmin_auto_upload` (Garmin Connect), `remote_bindings` (BLE remote control config JSON).

### ⚠️ HR/Power Targets: Percentage-Based ⚠️
All HR/Power targets stored as **% of threshold** (LTHR/FTP) so workouts survive threshold changes.
Fields: `hrTargetMinPercent/hrTargetMaxPercent`, `powerTargetMinPercent/powerTargetMaxPercent`, `autoAdjustMode` (NONE/HR/POWER).
Convert at runtime: `HeartRateZones.percentToBpm(step.hrTargetMinPercent, lthrBpm)`, `PowerZones.percentToWatts(...)`.
ExecutionStep convenience: `step.getHrTargetMinBpm(lthrBpm)`, `step.getPowerTargetMinWatts(ftpWatts)`.

### ⚠️ paceCoefficient vs Adjustment Coefficients ⚠️

| Coefficient | Location | Purpose | Changed By |
|-------------|----------|---------|------------|
| `paceCoefficient` | `ServiceStateHolder` | **CALIBRATION** — treadmill→actual speed (foot pod) | User only, Settings |
| `speedAdjustmentCoefficient` | `WorkoutExecutionEngine` | **DYNAMIC** — HR auto-adjust / manual buttons | Code, from telemetry |
| `inclineAdjustmentCoefficient` | `WorkoutExecutionEngine` | **DYNAMIC** — incline auto-adjust / manual buttons | Code, from telemetry |

**paceCoefficient is NEVER changed by code!** AdjustmentController is stateless for values — WorkoutExecutionEngine owns coefficients, calculates from telemetry, provides `getEffectiveSpeed(step)`.

### ⚠️ Zone Boundary Caches — MUST INVALIDATE ⚠️
`ServiceStateHolder.hrZone2Start..hrZone5Start` and `powerZone2Start..powerZone5Start` are **cached** (not computed on access). After writing `userLthrBpm`, `userFtpWatts`, or any `hrZone*StartPercent` / `powerZone*StartPercent`, you **MUST** call `invalidateHrZoneCaches()` / `invalidatePowerZoneCaches()`. Currently only `SettingsManager` writes these (loadSettings + save callback).

### ⚠️ Adjustment Scope (Per-Workout Setting) ⚠️
`Workout.adjustmentScope`: `ALL_STEPS` (default) or `ONE_STEP`.

**ALL_STEPS:** Coefficients are global within a phase. All steps share the same speed/incline coefficient. "I'm tired today, scale everything down."

**ONE_STEP:** Each step position has its own coefficient pair. On step transitions, the engine saves the current step's coefficients to a `Map<String, Pair<Double, Double>>` keyed by `stepIdentityKey`, then loads the next step's. Within repeat blocks, same-position children share keys across iterations (e.g., Run 1/4 through Run 4/4 all use key `r0_c0`).

**Phase boundaries always clear the map** (warmup→main, main→cooldown) regardless of scope.

**Chart rendering:** In ONE_STEP mode, the per-step coefficient map is passed through `WorkoutEngineManager` → `ChartManager` → `WorkoutChart`. The chart's `getSegmentCoefficients()` resolves each segment's coefficients by identity key. **CRITICAL:** ALL call sites of `setAdjustmentCoefficients()` (both `WorkoutEngineManager.updateChartCoefficients()` AND `HUDService.onWorkoutStateChanged()`) must pass the `perStepCoefficients` parameter — omitting it defaults to `null` which reverts to global mode.

### ⚠️ System Workouts & Phase Stitching ⚠️
System workouts identified by `systemWorkoutType` column (`"WARMUP"`/`"COOLDOWN"`), NOT name. Permanent, cannot be deleted/duplicated. Created by `WorkoutRepository.ensureSystemWorkoutsExist()` at startup. Regular workouts opt in via `useDefaultWarmup`/`useDefaultCooldown`.

**Stitching:** `WorkoutEngineManager.loadWorkoutStitched()` → loads main + warmup/cooldown → `WorkoutExecutionEngine.loadStitchedWorkout()` → flattens each phase → concatenates → stores phase counts.

**Coefficient reset** at phase boundaries (warmup→main, main→cooldown) prevents cross-phase contamination.

**Editor:** Sentinel rows with checkbox + summary. System workouts always visible regardless of search filter. Preview chart shows main only; live chart shows full stitched outline.

---

## Reusable UI Components

| Component | Location | Use For |
|-----------|----------|---------|
| **WorkoutChart** | `ui/components/WorkoutChart.kt` | Live overlay AND editor preview |
| **TouchSpinner** | `ui/components/TouchSpinner.kt` | Touch-friendly [-] value [+] input |
| **DualKnobZoneSlider** | `ui/components/DualKnobZoneSlider.kt` | 2-handle HR/Power min/max |
| **ZoneSlider** | `ui/components/ZoneSlider.kt` | Multi-handle zone boundaries |
| **OneKnobSlider** | `ui/components/OneKnobSlider.kt` | Single-handle slider |

---

## Architecture

```
HUDService (Orchestrator)
├── TelemetryManager        → GlassOS gRPC connection + subscriptions
├── WorkoutEngineManager    → Workout lifecycle (load, start, stop)
├── SettingsManager         → SharedPreferences + settings dialog
├── HrSensorManager         → HR sensor BLE connection
├── StrydManager            → Stryd foot pod BLE connection
├── RunPersistenceManager   → Crash recovery (periodic + on-pause saves)
├── FitFileExporter         → FIT file generation for Garmin Connect
├── GarminConnectUploader   → Garmin Connect OAuth + FIT/photo upload
├── TssCalculator           → TSS calculation (Power → HR → Pace fallback)
├── RemoteControlManager    → BLE remote bindings, action dispatch
├── DirConServer            → Direct connection protocol for external apps
├── BleFtmsServer           → BLE FTMS server for external fitness apps
└── UI Managers (Overlays)
    ├── HUDDisplayManager   → Main HUD metrics display
    ├── ChartManager        → Speed/incline/HR chart
    ├── WorkoutPanelManager → Workout progress panel
    ├── PopupManager        → Speed/incline adjustment popups
    └── BluetoothSensorDialogManager → BT sensor connection dialog

Data Layer
├── TreadmillHudDatabase    → Room DB (version 8)
├── WorkoutRepository       → Clean CRUD API (use this, not DAO directly)
├── Workout                 → Entity: metadata + systemWorkoutType, useDefaultWarmup/Cooldown, adjustmentScope
└── WorkoutStep             → Entity: step with HR/Power targets (% of threshold)

Domain Layer
├── WorkoutExecutionEngine  → Core workout logic
├── WorkoutStepFlattener    → Expands REPEAT blocks to ExecutionStep list
└── AdjustmentController    → Trend-aware HR/Power auto-adjustments
```

---

## Project Structure

```
app/src/main/java/io/github/avikulin/thud/
├── HUDService.kt              # Main foreground service
├── MainActivity.kt            # Main activity
├── BootReceiver.kt            # Auto-start on boot
├── RemoteControlAccessibilityService.kt  # BLE remote key interception
│
├── data/
│   ├── db/TreadmillHudDatabase.kt, WorkoutDao.kt, Converters.kt
│   ├── entity/Workout.kt, WorkoutStep.kt
│   ├── model/WorkoutDataPoint.kt
│   └── repository/WorkoutRepository.kt
│
├── domain/
│   ├── model/StepType.kt, DurationType.kt, AdjustmentType.kt, AutoAdjustMode.kt, EarlyEndCondition.kt, AdjustmentScope.kt, RemoteAction.kt
│   └── engine/
│       ├── WorkoutExecutionEngine.kt, ExecutionStep.kt, AdjustmentController.kt
│       ├── WorkoutStepFlattener.kt, WorkoutEvent.kt, WorkoutExecutionState.kt
│
├── service/
│   ├── GlassOsClient.kt           # gRPC client (mTLS, localhost:54321)
│   ├── ServiceStateHolder.kt      # Shared volatile state
│   ├── TelemetryManager.kt        # GlassOS comms
│   ├── WorkoutEngineManager.kt    # Workout control
│   ├── HUDDisplayManager.kt       # Main HUD overlay
│   ├── ChartManager.kt            # Chart overlay
│   ├── WorkoutPanelManager.kt     # Progress overlay
│   ├── PopupManager.kt            # Adjustment popups
│   ├── OverlayHelper.kt           # Overlay utilities
│   ├── SettingsManager.kt         # Settings
│   ├── HrSensorManager.kt         # HR sensor BLE connection
│   ├── StrydManager.kt            # Stryd foot pod BLE
│   ├── SavedBluetoothDevices.kt   # Unified BT device storage
│   ├── BluetoothSensorDialogManager.kt  # BT sensor dialog
│   ├── RemoteControlManager.kt    # BLE remote bindings + action dispatch
│   ├── RemoteControlBridge.kt     # Singleton connecting AccessibilityService ↔ HUDService
│   ├── RunPersistenceManager.kt   # Crash recovery persistence
│   ├── WorkoutRecorder.kt         # Thread-safe metrics recording
│   ├── ScreenshotManager.kt       # Auto-screenshot via MediaProjection API
│   ├── garmin/
│   │   └── GarminConnectUploader.kt # Garmin Connect OAuth + upload
│   ├── ble/
│   │   └── BleFtmsServer.kt       # BLE FTMS server for external apps
│   └── dircon/
│       ├── DirConServer.kt        # Direct connection protocol server
│       ├── ClientHandler.kt       # DirCon client handler
│       ├── DirConPacket.kt        # DirCon packet format
│       └── FtmsCharacteristics.kt # FTMS BLE characteristics
│
├── ui/
│   ├── ScreenshotPermissionActivity.kt  # Transparent activity for MediaProjection permission
│   ├── components/
│   │   ├── WorkoutChart.kt        # Reusable chart component
│   │   ├── TouchSpinner.kt        # Touch-friendly numeric input
│   │   ├── DualKnobZoneSlider.kt  # 2-handle slider for HR/Power min/max
│   │   ├── ZoneSlider.kt          # Multi-handle slider for zone boundaries
│   │   └── OneKnobSlider.kt       # Single-handle slider
│   ├── editor/
│   │   ├── WorkoutEditorActivityNew.kt, WorkoutEditorViewModel.kt
│   │   ├── InlineStepAdapter.kt, WorkoutListAdapter.kt
│   │   └── UndoRedoManager.kt     # Undo/redo for editor
│   ├── remote/
│   │   ├── RemoteControlActivity.kt       # Split-panel remote config (two columns)
│   │   ├── RemoteListAdapter.kt           # Left-pane remote list
│   │   ├── ActionBindingAdapter.kt        # tHUD actions column (Mode 1)
│   │   └── AndroidActionBindingAdapter.kt # Android actions column (Mode 2)
│   └── panel/WorkoutPanelView.kt
│
└── util/
    ├── HeartRateZones.kt          # HR zones + step type colors
    ├── PowerZones.kt              # Power zone utilities
    ├── PaceConverter.kt           # Pace/speed conversions
    ├── FileExportHelper.kt        # Centralized Downloads/tHUD file saving
    ├── FitFileExporter.kt         # FIT file generation for Garmin

    ├── TrainingMetricsCalculator.kt # Training load calculations
    ├── TssCalculator.kt           # TSS calculation (Power → HR → Pace fallback)
    └── StepBoundaryParser.kt      # Workout step parsing
```

---

## Domain Models

| Enum | Values |
|------|--------|
| **StepType** | WARMUP, RUN, RECOVER, REST, COOLDOWN, REPEAT |
| **DurationType** | TIME, DISTANCE |
| **AdjustmentType** | SPEED, INCLINE |
| **AutoAdjustMode** | NONE, HR, POWER |
| **EarlyEndCondition** | NONE, HR_RANGE |
| **AdjustmentScope** | ALL_STEPS, ONE_STEP |
| **RemoteAction** | SPEED_UP, SPEED_DOWN, INCLINE_UP, INCLINE_DOWN, BELT_START_PAUSE, BELT_STOP, NEXT_STEP, PREV_STEP, TOGGLE_MODE |
| **AndroidAction** | MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, VOLUME_UP, VOLUME_DOWN, MUTE, BACK, HOME, RECENT_APPS |

---

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s HUDService TelemetryManager WorkoutExecutionEngine
```

### ⚠️ BLE Remote Control — Key Architecture ⚠️

**AccessibilityService** (`RemoteControlAccessibilityService`) intercepts `KeyEvent`s globally via `flagRequestFilterKeyEvents`. It runs in the same app process as `HUDService` — no IPC needed.

**RemoteControlBridge** is a plain `object` singleton connecting the two services. Volatile fields ensure thread-safe reads without synchronization overhead.

**Device filtering is the FIRST check** in `onKeyEvent()`. Only devices whose `event.device.name` matches an explicitly configured remote are ever intercepted. All other input devices (BT keyboards, phone volume buttons, treadmill hardware keys) always pass through untouched.

**TOGGLE_MODE always works** regardless of `isActive` state — otherwise users couldn't switch back to take-over mode without touching the phone. Toggle Mode lives in a universal row above both columns in the config UI.

**Two modes with fallback-both-ways dispatch:**
- **Mode 1 (take-over, `isActive=true`):** tHUD bindings fire first. If no tHUD binding for this key, android bindings fire as fallback.
- **Mode 2 (pass-through, `isActive=false`):** Android bindings fire first. If no android binding, tHUD bindings fire as fallback.
- Same physical key CAN be bound in both columns — mode determines priority.

**Android action execution** happens directly in `RemoteControlAccessibilityService` (not in `RemoteControlManager`) because `performGlobalAction()` is only available on AccessibilityService. Media keys use `AudioManager.dispatchMediaKeyEvent()`, volume uses `AudioManager.adjustStreamVolume()`, navigation uses `performGlobalAction()`.

**Config is JSON in SharedPreferences** (key: `PREF_REMOTE_BINDINGS`), not Room DB. Structure: `{ "remotes": [{ "deviceName", "alias", "enabled", "bindings": [{ "action", "keyCode", "keyLabel", "value" }], "androidBindings": [{ "action", "keyCode", "keyLabel" }] }] }`. The `androidBindings` array is optional for backward compatibility. Parsing is duplicated across `RemoteControlManager`, `RemoteControlActivity`, and `RemoteControlAccessibilityService` because each may run independently.

**Speed/incline actions** call `TelemetryManager.setTreadmillSpeed()`/`setTreadmillIncline()` (respecting the speed/incline absolute rules). Speed is read as `state.currentSpeedKph * state.paceCoefficient` (adjusted), clamped to `[minSpeed * paceCoefficient, maxSpeed * paceCoefficient]`. Incline is read as `state.currentInclinePercent` (already effective), clamped to effective bounds.

### ⚠️ Full-Screen Activity Panel Lifecycle ⚠️
Any activity that opens from the HUD (workout editor, remote config) must manage overlay panel visibility. Use the static helpers — do NOT send raw intents:
```kotlin
override fun onResume() { super.onResume(); HUDService.notifyActivityForeground(this) }
override fun onPause() { super.onPause(); HUDService.notifyActivityBackground(this) }
override fun onDestroy() { HUDService.notifyActivityClosed(this); super.onDestroy() }
```
`savePanelState()` has a guard (`editorPanelStateSaved`) preventing double-save when `openXxx()` already saved before the activity's `onResume`.

---

## Garmin FIT Import Notes

**Watch sync required:** Garmin Connect does NOT calculate Training Status — the watch does. After uploading FIT: sync watch (downloads file) → watch processes → sync again (uploads metrics). Without this, no acute/chronic load contribution.

**Device serial MUST differ from user's watch** — matching serial causes watch to skip the file (dedup). Use any different serial.

### FIT Manufacturer & Stryd Developer Fields

**Manufacturer=1 (Garmin)** works with all platforms: Garmin Connect, Strava (Run), and Stryd PowerCenter.

Stryd PowerCenter acceptance requires developer fields mimicking the Stryd Connect IQ app format. `FitFileExporter` writes these automatically when power data exists:
- `DeveloperDataIdMesg` with Stryd UUID `18fb2cf0-1a4b-430d-ad66-988c847421f4`, app version 158
- Record-level Power (field 0, uint16, nativeFieldNum=7)
- Lap-level Lap Power (field 10, uint16, nativeFieldNum=7)
- Session-level CP (field 99, uint16, value=user FTP)

**CRITICAL:** `DeveloperDataIdMesg.setApplicationId(int, Byte)` corrupts bytes > 127 (signed Byte → 0xFF). Use `setFieldValue(ApplicationIdFieldNum, index, byteValue.toInt() and 0xFF)` instead.

**Do NOT use manufacturer=95 (Stryd)** — rejected everywhere. Keep `product=4565` (Forerunner 970) for device icon.

### ⚠️ Garmin Connect Upload — API Endpoints ⚠️
**FIT upload:** `POST connectapi.garmin.com/upload-service/upload/.fit` — OAuth2 Bearer, multipart field `userfile`.

**Photo upload:** `POST connectapi.garmin.com/activity-service/activity/{id}/image` — OAuth2 Bearer, multipart field `file`.

**CRITICAL:** Both use the **direct API** (`connectapi.garmin.com`) with `Authorization: Bearer`, `DI-Backend: connectapi.garmin.com`, `NK: NT`. Do NOT use `connect.garmin.com/gc-api/` (web proxy) — it requires session cookies + CSRF tokens and is fragile. The web proxy fallback exists in code but is not needed.

**Auth flow:** SSO WebView → ticket → OAuth1 (long-lived ~1yr) → OAuth2 (short-lived ~1hr, auto-refreshed). Tokens in `EncryptedSharedPreferences("GarminConnectTokens")` — separate from app's `"TreadmillHUD"` SharedPreferences.

**Consumer key/secret:** Fetched from `thegarth.s3.amazonaws.com/oauth_consumer.json` with hardcoded fallback.

### FTMS Settings
BLE/DirCon Broadcast + Control toggles (all default OFF). Control requires Broadcast. Custom device names. Servers restart on save.

### FIT Time in Zone (mesg 216)
**Garmin uses 7 zones (0-6)**, not 5. Zone boundaries: convert % to BPM first, THEN subtract 1 (not the reverse).

```kotlin
// CORRECT: convert to BPM first, then -1
val z1MaxBpm = (HeartRateZones.percentToBpm(z2StartPercent.toDouble(), userLthr) - 1).toShort()
// WRONG: subtract percent first
val z1MaxBpm = HeartRateZones.percentToBpm(z2StartPercent - 1, userLthr)  // ❌
```

Power zones follow the same 7-zone pattern. Time values in milliseconds.
