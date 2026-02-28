# ⚠️ If `CLAUDE-PRIVATE.md` file exists, read it NOW for maintainer-specific instructions. ⚠️
# ⚠️ NEVER COMMIT WITHOUT USER CONSENT!!! ⚠️
# ⚠️ ZERO CODE DUPLICATION — Extract shared logic into a single source of truth. Never copy-paste logic across files; if two places need the same behavior, create one shared method/utility and call it from both. ⚠️

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

## Critical Rules

### Android Resource Externalization
**IMPORTANT**: Never use inline styling in Kotlin code. Use `R.dimen.*`, `R.string.*`, `R.color.*` instead of hardcoded values.

### ⚠️ Button Styling — Use `OverlayHelper.createStyledButton()` ⚠️
**NEVER create raw `Button(context)` without styling!** All programmatic buttons must use `OverlayHelper.createStyledButton(context, text, colorResId, onClick)`. Default color is `button_secondary` (#444466). Uses `backgroundTintList` (preserves Material rounding) — NEVER `setBackgroundColor` on buttons (kills rounded corners). XML buttons must set `android:textColor="@color/text_primary"` and `android:backgroundTint="@color/button_secondary"`.

**Special colors (intentional exceptions only):**
| Color | Resource | Use For |
|-------|----------|---------|
| Green | `button_success` | Connect, Scan (BLE actions) |
| Green | `library_start_button` | RUN button |
| Red | `delete_button_bg` | Delete User |
| Red | `btn_close` | Delete Remote |

### SharedPreferences Name
**CRITICAL**: Always use `SettingsManager.PREFS_NAME` (dynamic, `@Volatile var`) to open SharedPreferences — never hardcode `"TreadmillHUD"`. The name is set per-profile by `ProfileManager` on startup (e.g., `"TreadmillHUD_a1b2c3d4"`). Similarly, Garmin tokens use `GarminConnectUploader.PREFS_NAME` (`"GarminConnectTokens_<id>"`). The profile registry uses its own separate `"tHUD_profiles"` prefs.

**⚠️ Android rejects path separators in SharedPreferences names.** Use flat naming (`"TreadmillHUD_<id>"`), never `"profiles/<id>/TreadmillHUD"`. Room DB paths CAN use absolute paths with separators.

### Single Source of Truth
**CRITICAL**: Before adding new data flows, check if a pathway already exists. Never send the same data through multiple callbacks.

| Data | Source | Flows To |
|------|--------|----------|
| Treadmill telemetry | `TelemetryManager` | `HUDService` → managers |
| HR data for adjustments | `onHeartRateUpdate()` | `WorkoutExecutionEngine.onHeartRateUpdate()` only |
| Speed/incline commands | `TelemetryManager.setTreadmillSpeed/Incline()` | THE ONLY PATH to GlassOsClient |
| Elapsed time | Treadmill via `onElapsedTimeUpdate()` | `WorkoutExecutionEngine.treadmillElapsedSeconds` |
| Workout state + coefficients | `WorkoutExecutionEngine` | `WorkoutEngineManager` → Chart |
| Run data + execution steps | `WorkoutRecorder` / `WorkoutExecutionEngine` | `createRunSnapshot()` → `FitFileExporter` |
| GlassOS connection state | `TelemetryManager.hasEverConnected` | `isReconnecting` flag |
| System workouts + phases | `WorkoutRepository` (DB) / `WorkoutExecutionEngine` | Engine stitching, chart, panel, coefficient reset |
| HR sensors + RR intervals | `HrSensorManager` → `state.connectedHrSensors` | `WorkoutRecorder` → `WorkoutDataPoint` → `FitFileExporter` |
| DFA alpha1 (per-sensor) | `dfaCalculators[mac]` → `state.dfaResults[mac]` | HUD box, popup, `WorkoutDataPoint`, FIT dev fields |
| Calculated HR | `HUDService.calcHrEmaValues` | Synthetic `CALC:<mac>` sensor → full pipeline |
| Per-sensor RR for FIT | `WorkoutRecorder.rrIntervalsBySensor` | `RunSnapshot` → `FitFileExporter` (one HrvMesg per file) |
| Pace progression | `WorkoutStep.paceEndTargetKph` | Engine ticking → `SpeedAdjusted` event → Treadmill |
| Garmin upload | `ServiceStateHolder` (SharedPrefs) | `HUDService` → `GarminConnectUploader` (OAuth + FIT/photo) |
| Remote control | `RemoteControlManager` (SharedPrefs JSON) | `RemoteControlBridge` → `AccessibilityService` |
| Profile registry + paths | `ProfileManager` (`tHUD_profiles` SharedPrefs) | `SettingsManager.PREFS_NAME`, `GarminConnectUploader.PREFS_NAME`, DB, exports |
| Profile switch + PIN auth | `HUDService.handleProfileSwitch()` | `setActiveProfile()` → `stopSelf()` → restart; PIN via `setAuthenticatedSwitch()` |
| Speed calibration | `WorkoutDataPoint` (raw + Stryd) | `SpeedCalibrationDao` → `SpeedCalibrationManager` → manual: `state.paceCoefficient` + `state.speedCalibrationB`; auto: `state.speedCalibrationC0..C3` (polynomial degree 1-3) |
| FIT Stryd speed flag | `ServiceStateHolder` (SharedPrefs) | `HUDService` → `FitFileExporter.exportWorkout(useStrydSpeed)` |
| Saved BT devices | `SavedBluetoothDevices` | Sensor managers, BT dialog |
| FTMS server settings | `ServiceStateHolder` (SharedPrefs) | `HUDService` → server start/stop |

### ⚠️ SPEED - ABSOLUTE RULES ⚠️

**Two independent calibration models:**
- **Manual mode** (`speedCalibrationAuto=false`): Linear `adjustedSpeed = a * rawSpeed + b` where `a` = `paceCoefficient` (slope) and `b` = `speedCalibrationB` (intercept). User-controlled via sliders.
- **Auto mode** (`speedCalibrationAuto=true`): Polynomial (degree 1-3) `adjustedSpeed = C0 + C1*x + C2*x² + C3*x³` using `speedCalibrationC0..C3` and `speedCalibrationDegree`. Coefficients recomputed after every run (even when auto is off), but only used live when auto is enabled. Inversion via Newton-Raphson.

**Conversion methods (SINGLE SOURCE OF TRUTH):**
- `state.rawToAdjustedSpeed(rawKph)` → branches on `speedCalibrationAuto`: polynomial `evaluatePolynomial()` or linear `rawKph * paceCoefficient + speedCalibrationB`
- `state.adjustedToRawSpeed(adjustedKph)` → branches on `speedCalibrationAuto`: `invertPolynomialNewtonRaphson()` or `(adjustedKph - speedCalibrationB) / paceCoefficient`

**NEVER multiply/divide by `paceCoefficient` directly!** Always use `rawToAdjustedSpeed()` / `adjustedToRawSpeed()`.

**SETTING speed:** NEVER call `glassOsClient.setSpeed()` directly! ALL speed commands go through `TelemetryManager.setTreadmillSpeed(adjustedKph)` which calls `state.adjustedToRawSpeed()` to get raw treadmill speed.

**READING speed:** NEVER use raw treadmill speed for internal logic! Always convert: `adjustedSpeed = state.rawToAdjustedSpeed(state.currentSpeedKph)`. All internal components work with adjusted speed.

**Flow OUT (setting):**
```
Engine event → WorkoutEngineManager → listener.onSetTreadmillSpeed(adjustedKph)
→ TelemetryManager.setTreadmillSpeed() ← adjustedToRawSpeed() → glassOsClient.setSpeed(rawKph)
```

**Flow IN (reading):**
```
TelemetryManager receives rawSpeed → state.currentSpeedKph = rawSpeed
→ HUDService: adjustedSpeed = state.rawToAdjustedSpeed(raw) → WorkoutEngineManager/Engine
```

**Raw speed used ONLY for:** HUD pace box lower text `(X.X kph)` display, `TelemetryManager.setTreadmillSpeed()` conversion, and `WorkoutDataPoint.rawTreadmillSpeedKph` (for calibration data collection).

### ⚠️ INCLINE - ABSOLUTE RULE ⚠️
**ALL incline throughout the app uses EFFECTIVE incline (outdoor equivalent)!**

- Effective = treadmill incline - adjustment (default adjustment = 1.0)
- 1% treadmill ≈ flat outdoor (no air resistance)

**Setting:** `TelemetryManager.setTreadmillIncline(effectivePercent)` adds `state.inclineAdjustment` before sending to GlassOS.
**Reading:** `TelemetryManager.onInclineUpdate(treadmillPercent)` subtracts `state.inclineAdjustment` before storing in `state.currentInclinePercent`.

All stored/displayed values (`state.currentInclinePercent`, popup menu, min/max, editor, StrydManager`) are effective incline.

### ⚠️ PACE PROGRESSION (Gradual Speed Change Within a Step) ⚠️
`WorkoutStep.paceEndTargetKph` (nullable, since DB version 9) enables gradual pace change from `paceTargetKph` to `paceEndTargetKph` over a step's duration. Pace-only (no incline progression).

**Engine:** `WorkoutExecutionEngine` tracks `currentProgressionBaseSpeed` (0 = inactive). Computes progress on each tick (time-based for TIME, distance-based for DISTANCE), rounds to 0.1 kph, emits `SpeedAdjusted` events.

**Coefficient interaction:** `getEffectiveSpeed()` returns `currentProgressionBaseSpeed * speedAdjustmentCoefficient`. `updateAdjustmentCoefficients()` uses the dynamic base (not the static `paceTargetKph`).

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

### ⚠️ PIN Auth & Guest Fallback ⚠️
PIN-protected profiles require authentication before switch. `ProfileManager.setAuthenticatedSwitch()` is set before `stopSelf()`, then `consumeAuthenticatedSwitch()` is checked on `HUDService.onCreate()`. If a PIN-protected profile starts **without** the flag (reboot, crash, manual restart), the profile data is loaded optimistically but `pendingPinChallenge` blocks `showHud()`. The PIN dialog is shown as a **gate** — no HUD panels, activities, or overlays are visible until authenticated. Correct PIN → `showHud()`. Wrong PIN → re-prompt. Cancel → `handleProfileSwitch(Guest)` (service restarts).

`PinDialogManager` is the reusable PIN entry overlay — used by `PopupManager` (profile switch), `SettingsManager` (set/change/remove PIN, delete user), and `HUDService` (startup challenge). PIN format: 4-8 digits, SHA-256 hashed via `ProfileManager.hashPin()`.

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

### DfaAlpha1Calculator (`util/DfaAlpha1Calculator.kt`)
Real-time DFA alpha1 from RR intervals. Thread-safe. Constructor: `windowDurationMs`, `artifactThresholdPercent`, `medianWindowSize`, `emaAlpha`. Methods: `addRrIntervals()`, `computeIfReady()` → `DfaResult`, `reset()`. Thresholds: >0.75 aerobic, 0.5–0.75 transition, <0.5 anaerobic.
**Artifact filter:** ALL physiologically valid intervals (200-2000ms) update the median baseline — prevents staleness during rapid HR transitions.

### ⚠️ Calculated HR from RR Intervals ⚠️
When enabled (`state.calcHrEnabled`), `HUDService.onRrIntervalsReceived()` computes HR from RR intervals and injects it as a **synthetic sensor** `"CALC:<mac>"` into `state.connectedHrSensors`. Params: `calcHrArtifactThreshold` (0-50%, default 20%), `calcHrMedianWindow` (3-21 odd, default 11), `calcHrEmaAlpha` (0.01-1.0, default 0.1).

**Synthetic MAC convention:** `CALC:<mac>` keys reuse the entire multi-sensor pipeline (WorkoutRecorder, FIT export, popup). UUID v3 from `"tHUD-HR:CALC:<mac>"` produces valid FIT developer field IDs.

**AVERAGE excludes CALC:** `computeAverageHrBpm()` and PopupManager average rows filter out `CALC:` entries to prevent double-counting. `hrSensorConnected` checked against non-CALC entries only.

**Cleanup:** CALC entries removed on real sensor disconnect, on feature disable, and EMA state + filter buffers cleared in both cases.

**Retroactive recalc:** On param change, `recalculateAllCalcHr()` replays stored RR through `processCalcHrBatch()` (shared pure filter method), patches `WorkoutDataPoint`s in-place.

### SpeedCalibrationManager (`util/SpeedCalibrationManager.kt`)
Stateless utility — filtering + regression math. Two regression modes:

**Linear (manual mode):** `computeRegression(points)` → `RegressionResult(a, b, r2, n)` or null if < 10 points. `computeR2(points, a, b)` → R² for manual mode feedback.

**Polynomial (auto mode):** `computePolynomialRegression(points, degree)` → `PolynomialResult(coefficients, degree, r2, n)` or null. Degree 1-3. Uses Vandermonde matrix normal equations with data centering/scaling for numerical stability. Gaussian elimination with partial pivoting (max 4×4 system). Monotonicity validation for degree 2-3 — falls back to lower degree if non-monotonic. `evaluatePolynomial(coefficients, x)` — Horner-style evaluation. `invertPolynomialNewtonRaphson(coefficients, targetY)` — for `adjustedToRawSpeed()`. `computePolynomialR2(points, coefficients)` — R² using polynomial model.

**Shared:** `extractPairs(dataPoints, runStartMs)` → valid calibration pairs.

**Data pipeline:** `WorkoutDataPoint` → `extractPairs()` → `SpeedCalibrationDao.insertAll()` → `getPointsForLastRuns(N)` → `computePolynomialRegression()` → `state.speedCalibrationC0..C3` (always recomputed after every run). Manual mode still uses `computeRegression()` → `state.paceCoefficient` + `state.speedCalibrationB`. DB retention: 90 runs max (trimmed on insert).

### OverlayHelper (`service/OverlayHelper.kt`)
Overlay window and dialog utilities. `createOverlayParams()`, `createDialogContainer/Title/Message/ButtonRow()`, `createStyledButton()` (see Button Styling rule), `calculateWidth/Height()`.

### FileExportHelper (`util/FileExportHelper.kt`)
Exports to Downloads/tHUD/<profile>/ via MediaStore. `saveToDownloads()`, `getTempFile()`, `getAbsoluteDir()`. `activeProfileSubfolder` (`@Volatile var`) set by `HUDService.onCreate()`. Subfolders: `ROOT`, `SCREENSHOTS`, `EXPORT`, `IMPORT`.

**⚠️ EncryptedSharedPreferences cannot be migrated by file rename!** Use `GarminConnectUploader.migrateFromLegacy()` (reads old name, writes new name via API). Called in `HUDService.onCreate()` after `updatePrefsName()`.

### SavedBluetoothDevices (`service/SavedBluetoothDevices.kt`)
Unified BT sensor storage. `getAll/getByType/save/remove/isSaved/getSavedMacs`. Types: `HR_SENSOR`, `FOOT_POD`.

### SettingsManager (`service/SettingsManager.kt`)
All SharedPreferences keys as constants. Key groups: `pace_coefficient`/`speed_calibration_b`/`speed_calibration_auto`/`speed_calibration_run_window`/`speed_calibration_degree`/`speed_calibration_c0..c3` (calibration), `hr_zone*_max`, `threshold_pace_kph`, `default_incline`, treadmill min/max, `fit_*`/`fit_use_stryd_speed`, `ftms_*`, `garmin_auto_upload`, `remote_bindings`, `calc_hr_*` (4 keys), `dfa_*` (5 keys), `chart_zoom_timeframe_minutes`. Settings dialog: 8 tabs (User, Treadmill, Zones, Auto-Adjust, FIT Export, FTMS, HR, Chart). Guest profile: User tab has disabled username, no PIN/Delete.

### ⚠️ HR/Power Targets: Percentage-Based ⚠️
All HR/Power targets stored as **% of threshold** (LTHR/FTP) so workouts survive threshold changes.
Fields: `hrTargetMinPercent/hrTargetMaxPercent`, `powerTargetMinPercent/powerTargetMaxPercent`, `autoAdjustMode` (NONE/HR/POWER).
Convert at runtime: `HeartRateZones.percentToBpm(step.hrTargetMinPercent, lthrBpm)`, `PowerZones.percentToWatts(...)`.
ExecutionStep convenience: `step.getHrTargetMinBpm(lthrBpm)`, `step.getPowerTargetMinWatts(ftpWatts)`.

### ⚠️ paceCoefficient vs Adjustment Coefficients ⚠️

| Coefficient | Location | Purpose | Changed By |
|-------------|----------|---------|------------|
| `paceCoefficient` | `ServiceStateHolder` | **CALIBRATION slope `a`** — manual linear model `adjusted = a * raw + b` | User (Settings slider) only |
| `speedCalibrationB` | `ServiceStateHolder` | **CALIBRATION intercept `b`** — manual linear model offset | User (Settings slider) only |
| `speedCalibrationC0..C3` | `ServiceStateHolder` | **CALIBRATION polynomial** — auto model `C0 + C1*x + C2*x² + C3*x³` | Auto-regression from Stryd after every run |
| `speedCalibrationDegree` | `ServiceStateHolder` | **Polynomial degree** (1, 2, or 3) for auto calibration | User (Settings spinner) |
| `speedAdjustmentCoefficient` | `WorkoutExecutionEngine` | **DYNAMIC** — HR auto-adjust / manual buttons | Code, from telemetry |
| `inclineAdjustmentCoefficient` | `WorkoutExecutionEngine` | **DYNAMIC** — incline auto-adjust / manual buttons | Code, from telemetry |

**paceCoefficient + speedCalibrationB:** Manual-only. User sets them via sliders. Default `a=1.0, b=0.0` = identity. Never auto-updated.

**speedCalibrationC0..C3:** Auto-only. Recomputed after every run via `SpeedCalibrationManager.computePolynomialRegression()` regardless of mode, but only used for live conversion when `speedCalibrationAuto = true`. Default `C0=0.0, C1=1.0, C2=0.0, C3=0.0` = identity polynomial.

**NEVER multiply/divide by `paceCoefficient` directly** — use `state.rawToAdjustedSpeed()` / `state.adjustedToRawSpeed()`.

AdjustmentController is stateless for values — WorkoutExecutionEngine owns coefficients, calculates from telemetry, provides `getEffectiveSpeed(step)`.

### ⚠️ Zone Boundary Caches — MUST INVALIDATE ⚠️
`ServiceStateHolder.hrZone2Start..hrZone5Start` and `powerZone2Start..powerZone5Start` are **cached** (not computed on access). After writing `userLthrBpm`, `userFtpWatts`, or any `hrZone*StartPercent` / `powerZone*StartPercent`, you **MUST** call `invalidateHrZoneCaches()` / `invalidatePowerZoneCaches()`. Currently only `SettingsManager` writes these (loadSettings + save callback).

### ⚠️ Adjustment Scope (Per-Workout Setting) ⚠️
`Workout.adjustmentScope`: `ALL_STEPS` (default, coefficients global within phase) or `ONE_STEP` (per-step coefficient map keyed by `stepIdentityKey`, saved/loaded on step transitions; repeat children share keys across iterations e.g. `r0_c0`).

**Phase boundaries always clear the map** (warmup→main, main→cooldown) regardless of scope.

**CRITICAL:** ALL call sites of `setAdjustmentCoefficients()` (both `WorkoutEngineManager.updateChartCoefficients()` AND `HUDService.onWorkoutStateChanged()`) must pass the `perStepCoefficients` parameter — omitting it defaults to `null` which reverts to global mode.

### ⚠️ System Workouts & Phase Stitching ⚠️
System workouts identified by `systemWorkoutType` column (`"WARMUP"`/`"COOLDOWN"`), NOT name. Permanent, cannot be deleted/duplicated. Created by `WorkoutRepository.ensureSystemWorkoutsExist()`. Regular workouts opt in via `useDefaultWarmup`/`useDefaultCooldown`.

**Stitching:** `WorkoutEngineManager.loadWorkoutStitched()` → flattens phases → concatenates → stores phase counts. Coefficient reset at phase boundaries prevents cross-phase contamination.

**Prev button is phase-limited:** Cannot cross phase boundaries backward. At boundary, restarts first step of current phase.

---

## Reusable UI Components

| Component | Location | Use For |
|-----------|----------|---------|
| **WorkoutChart** | `ui/components/WorkoutChart.kt` | Live overlay AND editor preview |
| **TouchSpinner** | `ui/components/TouchSpinner.kt` | Touch-friendly [-] value [+] input |
| **DualKnobZoneSlider** | `ui/components/DualKnobZoneSlider.kt` | 2-handle HR/Power min/max |
| **ZoneSlider** | `ui/components/ZoneSlider.kt` | Multi-handle zone boundaries |
| **OneKnobSlider** | `ui/components/OneKnobSlider.kt` | Single-handle slider |
| **SpeedCalibrationChart** | `ui/components/SpeedCalibrationChart.kt` | Speed calibration scatter plot |

---

## Architecture

```
ProfileManager (Singleton, initialized first in HUDService.onCreate)
├── Profile registry        → tHUD_profiles SharedPrefs (separate from user prefs)
├── Path derivation         → prefsName(), garminPrefsName(), dbPath(), profileDir()
├── CRUD                    → createProfile(), renameProfile(), deleteProfile()
├── Migration               → Moves existing data into "User" profile on first upgrade
├── PIN utilities           → hashPin(), verifyPin(), isValidPinFormat()
└── Auth switch flag        → setAuthenticatedSwitch(), consumeAuthenticatedSwitch()

HUDService (Orchestrator)
├── TelemetryManager        → GlassOS gRPC connection + subscriptions
├── WorkoutEngineManager    → Workout lifecycle (load, start, stop)
├── SettingsManager         → SharedPreferences + settings dialog
├── HrSensorManager         → HR sensor BLE connection (multi-sensor)
├── StrydManager            → Stryd foot pod BLE connection
├── RunPersistenceManager   → Crash recovery (periodic + on-pause saves)
├── FitFileExporter         → FIT file generation for Garmin Connect
├── GarminConnectUploader   → Garmin Connect OAuth + FIT/photo upload
├── TssCalculator           → TSS calculation (Power → HR → Pace fallback)
├── DfaAlpha1Calculator     → Real-time DFA alpha1 from RR intervals (per sensor)
├── RemoteControlManager    → BLE remote bindings, action dispatch
├── DirConServer            → Direct connection protocol for external apps
├── BleFtmsServer           → BLE FTMS server for external fitness apps
└── UI Managers (Overlays)
    ├── HUDDisplayManager   → Main HUD metrics display
    ├── ChartManager        → Speed/incline/HR chart
    ├── WorkoutPanelManager → Workout progress panel
    ├── PopupManager        → Speed/incline popups + user profile dropdown
    └── BluetoothSensorDialogManager → BT sensor connection dialog

Data Layer
├── TreadmillHudDatabase    → Room DB (version 10), multi-instance map keyed by absolute path
│                              Use getActiveInstance(context), NOT getInstance(context, path) directly
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
├── HUDService.kt, MainActivity.kt, BootReceiver.kt
├── RemoteControlAccessibilityService.kt
├── data/
│   ├── db/         TreadmillHudDatabase, WorkoutDao, SpeedCalibrationDao, Converters
│   ├── entity/     Workout, WorkoutStep, SpeedCalibrationPoint
│   ├── model/      WorkoutDataPoint
│   └── repository/ WorkoutRepository
├── domain/
│   ├── model/      StepType, DurationType, AdjustmentType, AutoAdjustMode,
│   │               EarlyEndCondition, AdjustmentScope, RemoteAction
│   └── engine/     WorkoutExecutionEngine, ExecutionStep, AdjustmentController,
│                   WorkoutStepFlattener, WorkoutEvent, WorkoutExecutionState
├── service/
│   ├── ProfileManager, GlassOsClient, ServiceStateHolder, TelemetryManager
│   ├── WorkoutEngineManager, SettingsManager, RunPersistenceManager
│   ├── HUDDisplayManager, ChartManager, WorkoutPanelManager, PopupManager
│   ├── OverlayHelper, PinDialogManager, ScreenshotManager
│   ├── HrSensorManager, StrydManager, SavedBluetoothDevices
│   ├── BluetoothSensorDialogManager, WorkoutRecorder
│   ├── RemoteControlManager, RemoteControlBridge
│   ├── garmin/     GarminConnectUploader
│   ├── ble/        BleFtmsServer
│   └── dircon/     DirConServer, ClientHandler, DirConPacket, FtmsCharacteristics
├── ui/
│   ├── ScreenshotPermissionActivity
│   ├── components/ WorkoutChart, TouchSpinner, DualKnobZoneSlider, ZoneSlider,
│   │               OneKnobSlider, SpeedCalibrationChart
│   ├── editor/     WorkoutEditorActivityNew, WorkoutEditorViewModel,
│   │               InlineStepAdapter, WorkoutListAdapter, UndoRedoManager
│   ├── remote/     RemoteControlActivity, RemoteListAdapter,
│   │               ActionBindingAdapter, AndroidActionBindingAdapter
│   └── panel/      WorkoutPanelView
└── util/
    ├── HeartRateZones, PowerZones, PaceConverter, FileExportHelper
    ├── FitFileExporter, TrainingMetricsCalculator, TssCalculator
    ├── DfaAlpha1Calculator, SpeedCalibrationManager, StepBoundaryParser
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
| **ChartZoomMode** | TIMEFRAME, MAIN_PHASE, FULL *(in `WorkoutChart`)* |
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

**AccessibilityService** (`RemoteControlAccessibilityService`) intercepts `KeyEvent`s globally. Runs in same process as `HUDService` — no IPC. **RemoteControlBridge** singleton connects the two services.

**Device filtering is the FIRST check** in `onKeyEvent()`. Only explicitly configured remotes are intercepted. All other input devices always pass through.

**TOGGLE_MODE always works** regardless of `isActive` state. Two modes with fallback: Mode 1 (take-over): tHUD first → android fallback. Mode 2 (pass-through): android first → tHUD fallback. Same key CAN be bound in both columns.

**Android actions** execute in `RemoteControlAccessibilityService` (not Manager) — `performGlobalAction()` only available there. Config is JSON in SharedPreferences (key: `PREF_REMOTE_BINDINGS`), parsing duplicated across 3 classes (each may run independently).

**Speed/incline actions** use `TelemetryManager.setTreadmillSpeed()`/`setTreadmillIncline()` (respecting absolute rules). Speed clamped to `[rawToAdjustedSpeed(min), rawToAdjustedSpeed(max)]`.

### ⚠️ Full-Screen Activity Panel Lifecycle ⚠️
Any activity that opens from the HUD must manage overlay panel visibility. Use the static helpers — do NOT send raw intents:
```kotlin
override fun onResume() { super.onResume(); HUDService.notifyActivityForeground(this) }
override fun onPause() { super.onPause(); HUDService.notifyActivityBackground(this) }
override fun onDestroy() { HUDService.notifyActivityClosed(this); super.onDestroy() }
```
`savePanelState()` has a guard (`editorPanelStateSaved`) preventing double-save when `openXxx()` already saved before the activity's `onResume`.

---

## Garmin FIT Export & Upload

**Watch sync required:** Upload → sync watch (downloads) → watch processes → sync again (uploads metrics). **Device serial MUST differ from user's watch** (dedup skip).

### FIT Manufacturer & Stryd Dev Fields
**Manufacturer=1 (Garmin)** works everywhere. **Do NOT use manufacturer=95 (Stryd)** — rejected. Product=4565 (Forerunner 970).
Stryd dev fields (UUID `18fb2cf0-1a4b-430d-ad66-988c847421f4`, app v158): Record Power (field 0, uint16, native 7), Lap Power (field 10, uint16, native 7), Session CP (field 99, uint16).
**CRITICAL:** `setApplicationId(int, Byte)` corrupts bytes > 127. Use `setFieldValue(..., byteValue.toInt() and 0xFF)`.

### Multi-HR Sensor Dev Fields
Each HR sensor gets `DeveloperDataIdMesg` with **UUID v3** from `"tHUD-HR:<MAC>"`. Lazy registration via `WorkoutRecorder.resolveOrRegister()`. Per-data-point storage uses integer indices: `allHrSensors: Map<Int, Int>`, `primaryHrIndex: Int`, `dfaAlpha1BySensor: Map<Int, Double>`.

### FIT HRV Export (Multi-File)
FIT supports one RR stream per file. `HrvMesg` holds up to 5 RR values (seconds, Float). **MUST be interleaved with Record messages** (not batched). When 2+ RR-capable sensors → one FIT file per sensor (identical workout, different HRV). Only DFA-primary sensor's file uploaded to Garmin. DFA alpha1 written as dev field 1 (UINT16, scale 1000).

### FIT Stryd Speed Preprocessing + Treadmill Dev Fields
**"Use Stryd speed" flag** (`state.fitUseStrydSpeed`, default ON): `recalculateWithStrydSpeed()` preprocesses data points — picks `strydSpeedKph` (if > 0) else `speedKph`, recalculates cumulative distance/elevation. Modified list cascades through all downstream code.

**Treadmill speed dev fields** (UUID v3 from `"tHUD-SpeedCalibration"`):
- Field 0: **Raw Treadmill Speed** (UINT16, "m/s", scale 1000)
- Field 1: **Calibrated Treadmill Speed** (UINT16, "m/s", scale 1000)

4 speed streams per record: native `enhancedSpeed`, dev Raw, dev Calibrated, dev Stryd.

### FIT Grade/Incline
Per-record `setGrade(%)`, per-lap `setAvgGrade(%)`, per-session `setAvgGrade(%)`. Note: `maxGrade` does NOT exist on `LapMesg`/`SessionMesg` — only `avgGrade` and `totalAscent`.

### ⚠️ Garmin Connect Upload — API Endpoints ⚠️
**FIT:** `POST connectapi.garmin.com/upload-service/upload/.fit` — Bearer, multipart `userfile`.
**Photo:** `POST connectapi.garmin.com/activity-service/activity/{id}/image` — Bearer, multipart `file`.
**CRITICAL:** Use **direct API** (`connectapi.garmin.com`) with `Authorization: Bearer`, `DI-Backend: connectapi.garmin.com`, `NK: NT`. Do NOT use `connect.garmin.com/gc-api/` (web proxy).
**Auth:** SSO WebView → ticket → OAuth1 (~1yr) → OAuth2 (~1hr, auto-refreshed). Tokens in `EncryptedSharedPreferences("GarminConnectTokens")`.

### FIT Time in Zone (mesg 216)
**Garmin uses 7 zones (0-6)**, not 5. Convert % to BPM first, THEN subtract 1:
```kotlin
val z1MaxBpm = (HeartRateZones.percentToBpm(z2StartPercent.toDouble(), userLthr) - 1).toShort() // CORRECT
```
Power zones: same 7-zone pattern. Time values in milliseconds.

### FTMS Settings
BLE/DirCon Broadcast + Control toggles (all default OFF). Control requires Broadcast. Custom device names. Servers restart on save.
