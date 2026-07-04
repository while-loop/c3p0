# Project Goals - C3P0

## Phase 1: Foundation & BLE Communication [COMPLETED]
- [x] Implement shared Bluetooth Controller with coroutines/Flow support.
- [x] Implement `WalkingPadManager` for C2 (control speed, read stats).
- [x] Implement `GarminManager` for Venu 3 (read heart rate).
- [x] Create mock implementations for testing BLE logic without hardware.
- [x] Implement persistent pairing to remember device addresses.
- [x] Deduplicate BLE scan results by device address and show closest devices first by RSSI.
- [x] Show selected and connected status in pairing/profile surfaces.
- [x] Reconnect saved treadmill/watch addresses when opening session or profile screens.

## Phase 2: Data Persistence & Health Connect [COMPLETED]
- [x] Set up Room database for sessions, history, and settings.
    - [x] Store time-series metrics: Heart Rate, Speed, and Cadence.
- [x] Integrate Health Connect for writing completed walking sessions with steps and distance.
- [x] Read latest body weight from Health Connect for calories estimates.
- [x] Remove app-side heart-rate writes to Health Connect; watches remain the source of HR records.
- [x] Implement "Normalized Steps" algorithm using Health Connect data.
- [x] Ensure historical viewing of all session metrics (steps/HR/distance/time/SPM).

## Phase 3: Session Logic & Auto-Speed [COMPLETED]
- [x] Implement Session management (start/stop/pause).
- [x] Add pause/resume controls; pause stops active stats and metric collection.
- [x] Require a 5-second long press to stop an active session.
- [x] Warn before starting/resuming when the watch or WalkingPad is inactive, with options to pair, continue, or never ask again.
- [x] Implement Auto-Speed algorithm for Zone 2 HR training.
- [x] Implement Ongoing Notification via Foreground Service.
- [x] Keep WalkingPad units synchronized with the stored app unit setting at settings changes and session boundaries.

## Phase 4: UI Development [COMPLETED]
- [x] Build Session Screen (real-time stats, speed control, active HR chart, calories, elapsed/active session stats).
- [x] Build Stats Screen (history list, session details).
- [x] Build Profile Screen (integrations, goals, current device status, unit settings).
- [x] Build Pairing Screen (scan and save devices).
- [x] Add permission explanation bottom sheets before permission requests.
- [x] Add heart-rate history chart with y-axis labels and zone-colored line segments.

## Phase 5: Cloud Sync & Final Polish [COMPLETED]
- [x] Integrate Google Drive via Android Auto Backup.
- [x] Configure backup rules for Room DB, Room WAL/SHM files, DataStore preferences, and SharedPreferences.
- [x] Persist the backup toggle and call `BackupManager.dataChanged()` on app cold start, settings changes, session progress, and session completion.
- [x] Implement Green/Gray connectivity status indicators in UI.
- [x] Add GitHub Actions CI that builds debug APKs on every push and publishes the APK artifact.
