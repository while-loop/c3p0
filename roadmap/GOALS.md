# Project Goals - C3P0

## Phase 1: Foundation & BLE Communication
- [ ] Implement shared Bluetooth Controller with coroutines/Flow support.
- [ ] Implement `WalkingPadManager` for C2 (control speed, read stats).
- [ ] Implement `GarminManager` for Venu 3 (read heart rate).
- [ ] Create mock implementations for testing BLE logic without hardware.

## Phase 2: Data Persistence & Health Connect
- [ ] Set up Room database for sessions, history, and settings.
- [ ] Integrate Health Connect for writing session data (steps, distance).
- [ ] Implement "Normalized Steps" algorithm using Health Connect data.

## Phase 3: Session Logic & Auto-Speed
- [ ] Implement Session management (start/stop/pause).
- [ ] Implement Auto-Speed algorithm for Zone 2 HR training.
- [ ] Implement Daily Step Goals with auto-stop and cooldown.

## Phase 4: UI Development
- [ ] Build Session Screen (real-time stats, speed control).
- [ ] Build Stats Screen (history charts, session details).
- [ ] Build Profile/Settings Screen (integrations, goals).

## Phase 5: Cloud Sync & Backup
- [ ] Integrate Google Drive API for app data backup.
- [ ] Implement manual/auto sync logic for the database.
