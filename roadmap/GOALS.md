# Project Goals - C3P0

## Phase 1: Foundation & BLE Communication [COMPLETED]
- [x] Implement shared Bluetooth Controller with coroutines/Flow support.
- [x] Implement `WalkingPadManager` for C2 (control speed, read stats).
- [x] Clamp WalkingPad speed commands and decode status counters defensively.
- [x] Implement `GarminManager` for Venu 3 (read heart rate).
- [x] Create mock implementations for testing BLE logic without hardware.
- [x] Implement persistent pairing to remember device addresses.
- [x] Deduplicate BLE scan results by device address and batch closest-first RSSI sorting every 5 seconds.
- [x] Show selected and connected status in pairing/profile surfaces.
- [x] Reconnect saved treadmill/watch addresses when opening session or profile screens.

## Phase 2: Data Persistence & Health Connect [COMPLETED]
- [x] Set up Room database for sessions, history, and settings.
    - [x] Store time-series metrics: Heart Rate, Speed, and real step-delta Cadence.
- [x] Integrate Health Connect for writing completed walking sessions with steps and distance.
- [x] Read latest body weight from Health Connect for calories estimates.
- [x] Remove app-side heart-rate read/write permissions from Health Connect; watches remain the source of HR records.
- [x] Scope Health Connect permission checks by operation and surface Health Connect settings for revocation.
- [x] Implement and surface "Normalized Steps" using Health Connect data in session history.
- [x] Show daily Health Connect step history with raw and normalized totals.
- [x] Ensure historical viewing of session aggregates and metric summaries (steps/HR/distance/time/SPM).

## Phase 3: Session Logic & Auto-Speed [COMPLETED]
- [x] Implement Session management (start/stop/pause).
- [x] Add pause/resume controls; pause stops active stats and metric collection.
- [x] Require a 5-second long press to stop an active session.
- [x] Warn before starting/resuming when the watch, live HR broadcast, or WalkingPad is inactive, with options to pair, continue, or never ask again.
- [x] Keep live and persisted session distance/step totals aligned to session-start deltas.
- [x] Implement and wire Auto-Speed algorithm for Zone 2 HR training from the session Automatic mode.
- [x] Implement Ongoing Notification via Foreground Service.
- [x] Send WalkingPad start/stop commands when sessions start/stop.
- [x] Keep WalkingPad units synchronized with the stored app unit setting at settings changes and session boundaries.

## Phase 4: UI Development [COMPLETED]
- [x] Build Session Screen (real-time stats, speed control, active HR chart, calories, elapsed/active session stats).
- [x] Build Stats Screen (history list, selected-session details, normalized step breakdown).
- [x] Build Profile Screen (integrations, goals, current device status, unit settings).
- [x] Build Pairing Screen (scan and save devices).
- [x] Persist profile step goal and age settings.
- [x] Add permission explanation bottom sheets before permission requests.
- [x] Add heart-rate history chart with y-axis labels and zone-colored line segments.

## Phase 5: Cloud Sync & Final Polish [COMPLETED]
- [x] Integrate Google Drive via Android Auto Backup.
- [x] Configure backup rules for Room DB, Room WAL/SHM files, DataStore preferences, and SharedPreferences.
- [x] Persist the backup toggle and call `BackupManager.dataChanged()` on app cold start, settings changes, session progress, and session completion.
- [x] Throttle session-progress backup requests without repeated DataStore reads.
- [x] Implement Green/Gray connectivity status indicators in UI.
- [x] Add GitHub Actions CI that builds debug APKs on every push and publishes the APK artifact.
