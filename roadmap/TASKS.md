# Task List

## Completed
- [x] **Initialize Foundation**: Set up Hilt, Room, DataStore, and Health Connect dependencies.
- [x] **BLE Core**: Implemented `BleScanner`, `BleConnection`, and `BleModule` (DI).
- [x] **WalkingPad Support**: Reverse-engineered C2 protocol, CRC calculation, and status parsing.
- [x] **WalkingPad Guardrails**: Decode 24-bit counters explicitly and clamp speed commands at the BLE manager boundary.
- [x] **Garmin Support**: Implemented standard Heart Rate Service (HRS) integration.
- [x] **Session Logic**: Created `SessionManager` and `SessionService` (Foreground) for ongoing tracking.
- [x] **Session Pause/Stop Safety**: Added pause/resume controls and made stop require a 5-second long press.
- [x] **Session Control Commands**: Start, pause/resume, and stop now send WalkingPad control commands, not just app-local state changes.
- [x] **Inactive Device Warning**: Added a bottom sheet before start/resume when the watch or WalkingPad is inactive, including pair, continue, and never-ask-again controls.
- [x] **Auto-Speed**: Implemented smooth Zone 2 adjustment algorithm with moving average smoothing and wired Automatic mode to enable it.
- [x] **Data Persistence**: Room DB schema for sessions and time-series metrics.
- [x] **Session Aggregates**: Persist session distance/steps as session deltas, plus calories, average HR, max HR, and real cadence.
- [x] **Live Session Deltas**: Session-screen distance and steps use current-session deltas instead of lifetime WalkingPad counters.
- [x] **Session Metrics UI**: Added elapsed time, calories, current HR, session average HR, and heart-rate history chart with y-axis labels and zone-colored line segments.
- [x] **UI Implementation**: Dashboard, Stats, Profile, and Pairing screens.
- [x] **Stats Details**: Added selected-session history details and normalized Health Connect step breakdown.
- [x] **Permissions UX**: Added bottom sheets to explain permission requests before invoking system permission prompts.
- [x] **Persistent Pairing**: Logic to save and remember BLE addresses across restarts.
- [x] **BLE Pairing Polish**: Deduplicated scan results, sorted devices closest-to-furthest by RSSI, and surfaced selected/connected status in pairing and profile screens.
- [x] **Unit Settings**: Added imperial/metric settings, defaulted to imperial, and synchronized WalkingPad units from app storage on setting changes and session boundaries.
- [x] **Profile Persistence**: Persisted age and daily step goal through DataStore and backup.
- [x] **Health Connect Weight**: Added weight refresh from Health Connect on cold start/resume and from the profile refresh button.
- [x] **Health Connect Session Export**: Write completed walking sessions, steps, and distance to Health Connect; do not request or write heart-rate records.
- [x] **Health Connect Permissions**: Scoped runtime checks by operation, refreshed permission state on profile resume, and opened Health Connect settings when the user wants to revoke access.
- [x] **Cloud Sync**: Configured Android Auto Backup rules for Google Drive, including Room DB files and DataStore preferences.
- [x] **Backup Requests**: Persisted the backup toggle and request backups on app cold start, backup toggle enable, persisted settings changes, active session progress, and session completion.
- [x] **Backup Throttling**: Avoid per-sample DataStore reads when a throttled backup request is too soon, while still allowing the first request after app start.
- [x] **CI Debug APKs**: Added GitHub Actions workflow that assembles a debug APK on every push and uploads it as a retained artifact for remote install testing.

## Backlog (Future Refinements)
- [ ] **Polynomial Smoothing**: Refine the Auto-Speed algorithm from proportional to a polynomial curve for even smoother transitions.
- [ ] **Cooldown Mode**: Implement N-minute gradual speed reduction when step goal is reached.
- [ ] **Visualization**: Re-integrate Vico charts for historical session analysis.
- [ ] **Energy Calculation**: Replace the current MET estimate with a more precise Kcal calculation based on weight, speed, HR, and active time.
- [ ] **Elapsed vs Active Time**: Decide whether to display both wall-clock elapsed time and active/moving time. Current session timers pause with the session; many fitness apps keep elapsed wall-clock time separate from active time.
- [ ] **Backup Restore Verification**: Manually verify Android Auto Backup restore behavior on a real signed install path; restore timing is controlled by Android and Google backup services.
- [ ] **Unit Tests**: Add comprehensive test suite for `AutoSpeedController`.
