# Architectural Decisions & Rationale

## 1. Bluetooth (BLE) Architecture
- **Shared Controller**: We use a modular BLE architecture where `BleConnection` handles the low-level GATT logic and `TreadmillManager` / `HeartRateManager` handle the high-level protocol parsing.
- **Mocks**: Every manager has a mock implementation (`MockTreadmillManager`) to allow UI and logic development without physical hardware.
- **Pairing List Semantics**: BLE scan results are deduplicated by device address and sorted by RSSI so the nearest physical device appears first. Pairing and profile surfaces show selected and connected status separately because a saved device is not necessarily connected.
- **Saved Device Reconnect**: Session and profile surfaces attempt to reconnect saved treadmill/watch addresses when opened so the app can recognize devices that are already paired with Android.
- **Reconnect Cleanup**: Managers close any existing GATT connection before connecting to a new saved or selected address.

## 2. Session Management
- **Foreground Service**: Sessions run in `SessionService` to prevent the OS from killing the app during long workouts. This is required for reliable BLE data collection and Health Connect exports.
- **Persistence**: Real-time metrics (HR, Speed, Cadence) are saved to Room as they arrive, ensuring data isn't lost if the app crashes.
- **Pause Semantics**: Pausing a session stops the WalkingPad, pauses elapsed/active stat collection, skips Room metric writes, and excludes paused HR readings from the average. Resuming restarts the WalkingPad and continues the same session without resetting stats.
- **Stop Safety**: Stopping a session requires a 5-second long press to prevent accidental session loss.
- **Device Commands**: Session start sends WalkingPad start, pause sends stop, resume sends start, and stop captures final counters before sending stop.
- **Session Aggregates**: Stored session totals use deltas from the start counters. Calories use a MET estimate based on active duration, body weight, and average speed. HR aggregates come from active readings only. Cadence uses step deltas over elapsed metric time.
- **Inactive Devices**: Starting/resuming with an inactive watch or WalkingPad shows a bottom sheet with Pair, Continue, and Never Ask Again options.
- **Time Display Caveat**: Current session time behaves like active/moving time because it pauses with the session. If a separate wall-clock elapsed time is needed, add a second stat instead of changing active time semantics.

## 3. Data Strategy
- **Health Connect**: Chosen over Google Fit as the primary health data hub for modern Android devices. We write completed walking sessions, steps, and distance. We read body weight for calorie estimates and steps for normalization. We do not request or write heart-rate records; the watch should remain the source of HR data in Health Connect.
- **DataStore**: Used for lightweight persistence like BLE device addresses, unit preferences, inactive-warning preference, backup toggle, profile age, daily step goal, and cached body weight. Room handles the heavy lifting for session history.
- **Unit Source of Truth**: The app's stored unit setting is the source of truth. WalkingPad units are corrected when settings change, when a session starts/resumes/pauses, and when reported device units diverge.
- **Auto Backup**: We rely on Android's native Google Drive Auto Backup to sync Room DB files and DataStore preferences. Backup rules include `c3p0.db`, `c3p0.db-shm`, `c3p0.db-wal`, `datastore/settings.preferences_pb`, and SharedPreferences.
- **Backup Requests**: When Google Drive backup is enabled, the app calls `BackupManager.dataChanged()` on cold start, persisted settings changes, active session progress on a 5-minute throttle, and session completion. This requests Android to schedule a backup; it does not force an immediate upload or restore.

## 4. UI/UX
- **Jetpack Compose**: Used for all UI. We opted for a simple Dashboard with "Status Dots" to provide immediate feedback on device connectivity.
- **Auto-Speed Algorithm**: Implemented as a Proportional Controller with a Moving Average to ensure treadmill adjustments are smooth and not jittery.
- **Permission Rationale**: Code paths that require Android permissions should show a bottom sheet explaining the request before invoking the system prompt.
- **Session Dashboard Density**: Session stats are compact tiles because the screen now shows distance, steps, active time, calories, current HR, average HR, speed controls, device status, and the HR chart.
- **Heart-Rate Chart**: The session chart includes y-axis labels and zone-colored line segments for Zone 0 through Zone 4.
- **Stats History**: Tapping a historical session shows stored aggregates, metric-derived fallback summaries, and Health Connect normalized step counts when available.

## 5. CI & Distribution
- **Debug APK Workflow**: GitHub Actions builds a debug APK on every push and uploads it as an artifact for remote install testing. Release APKs are deferred until signing keys exist.
- **Artifact Retention**: CI artifacts should use a finite retention period because artifact storage can count against repository/account limits.
