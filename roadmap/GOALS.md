# Project Goals - C3P0

## Phase 1: Foundation & BLE Communication [COMPLETED]
- [x] Implement shared Bluetooth Controller with coroutines/Flow support.
- [x] Implement `WalkingPadManager` for C2 (control speed, read stats).
- [x] Implement `GarminManager` for Venu 3 (read heart rate).
- [x] Create mock implementations for testing BLE logic without hardware.
- [x] **New**: Implement persistent pairing to remember device addresses.

## Phase 2: Data Persistence & Health Connect [COMPLETED]
- [x] Set up Room database for sessions, history, and settings.
    - [x] Store time-series metrics: Heart Rate, Speed, and Cadence.
- [x] Integrate Health Connect for writing session data (steps, distance).
- [x] Implement "Normalized Steps" algorithm using Health Connect data.
- [x] Ensure historical viewing of all session metrics (steps/HR/distance/time/SPM).

## Phase 3: Session Logic & Auto-Speed [COMPLETED]
- [x] Implement Session management (start/stop/pause).
- [x] Implement Auto-Speed algorithm for Zone 2 HR training.
- [x] Implement Ongoing Notification via Foreground Service.

## Phase 4: UI Development [COMPLETED]
- [x] Build Session Screen (real-time stats, speed control).
- [x] Build Stats Screen (history list, session details).
- [x] Build Profile Screen (integrations, goals).
- [x] Build Pairing Screen (scan and save devices).

## Phase 5: Cloud Sync & Final Polish [COMPLETED]
- [x] Integrate Google Drive via Android Auto Backup.
- [x] Configure backup rules for Room DB and SharedPreferences.
- [x] Implement Green/Gray connectivity status indicators in UI.
