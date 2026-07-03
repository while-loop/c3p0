# Task List

## Completed
- [x] **Initialize Foundation**: Set up Hilt, Room, DataStore, and Health Connect dependencies.
- [x] **BLE Core**: Implemented `BleScanner`, `BleConnection`, and `BleModule` (DI).
- [x] **WalkingPad Support**: Reverse-engineered C2 protocol, CRC calculation, and status parsing.
- [x] **Garmin Support**: Implemented standard Heart Rate Service (HRS) integration.
- [x] **Session Logic**: Created `SessionManager` and `SessionService` (Foreground) for ongoing tracking.
- [x] **Auto-Speed**: Implemented smooth Zone 2 adjustment algorithm with moving average smoothing.
- [x] **Data Persistence**: Room DB schema for sessions and time-series metrics.
- [x] **UI Implementation**: Dashboard, Stats, Profile, and Pairing screens.
- [x] **Persistent Pairing**: Logic to save and remember BLE addresses across restarts.
- [x] **Cloud Sync**: Configured Android Auto Backup rules for Google Drive.

## Backlog (Future Refinements)
- [ ] **Polynomial Smoothing**: Refine the Auto-Speed algorithm from proportional to a polynomial curve for even smoother transitions.
- [ ] **Cooldown Mode**: Implement N-minute gradual speed reduction when step goal is reached.
- [ ] **Visualization**: Re-integrate Vico charts for historical session analysis.
- [ ] **Energy Calculation**: Implement precise Kcal calculation based on weight/speed/HR.
- [ ] **Unit Tests**: Add comprehensive test suite for `AutoSpeedController`.
