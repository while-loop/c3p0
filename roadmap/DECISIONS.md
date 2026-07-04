# Architectural Decisions & Rationale

## 1. Bluetooth (BLE) Architecture
- **Shared Controller**: We use a modular BLE architecture where `BleConnection` handles the low-level GATT logic and `TreadmillManager` / `HeartRateManager` handle the high-level protocol parsing.
- **Mocks**: Every manager has a mock implementation (`MockTreadmillManager`) to allow UI and logic development without physical hardware.
- **Pairing List Semantics**: BLE scan results are deduplicated by device address. A device is hidden until it has multiple scan events, then qualified devices are revealed gradually in a stable order. Reveal priority uses scan event count first and average RSSI second; once a device appears, later RSSI updates do not move its row. Pairing and profile surfaces show selected and connected status separately because a saved device is not necessarily connected.
- **Explicit Device Role Selection**: Selecting a BLE device opens a neutral role-choice bottom sheet for WalkingPad or Watch. The app saves the chosen role explicitly instead of inferring it from the advertised name, and if the same address was previously saved under the other role it is cleared from that slot.
- **Saved Device Reconnect**: Session and profile surfaces attempt to reconnect saved treadmill/watch addresses when opened so the app can recognize devices that are already paired with Android.
- **Reconnect Cleanup**: Managers close any existing GATT connection before connecting to a new saved or selected address.
- **WalkingPad BLE Control Loop**: The WalkingPad does not rely on unsolicited status updates. After service discovery, the manager enables notifications, polls current status about every 750 ms, and serializes outbound commands with roughly 700 ms of spacing so preference sync, start/stop, mode, and speed commands are not sent back-to-back.
- **Bluetooth Error Visibility**: BLE scan, connection, service discovery, notification, command-write failures, missing WalkingPad status replies, and WalkingPad commands that do not appear in subsequent status are reported through a singleton error reporter and shown as persistent stacked error cards in the root UI. Errors do not auto-dismiss, making transient asynchronous failures visible for troubleshooting.
- **WalkingPad Command Bounds**: Speed changes are clamped in the UI, auto-speed controller handoff, mock manager, and real BLE manager so no caller can send an out-of-range speed.
- **WalkingPad Counter Decoding**: Status notifications decode 24-bit time, distance, and step counters explicitly to avoid operator-precedence ambiguity.

## 2. Session Management
- **Foreground Service**: Sessions run in `SessionService` to prevent the OS from killing the app during long workouts. This is required for reliable BLE data collection and Health Connect exports.
- **Persistence**: Real-time metrics (HR, Speed, Cadence) are saved to Room as they arrive, ensuring data isn't lost if the app crashes.
- **Pause Semantics**: Pausing a session stops the WalkingPad, pauses elapsed/active stat collection, skips Room metric writes, and excludes paused HR readings from the average. Resuming restarts the WalkingPad and continues the same session without resetting stats.
- **Stop Safety**: Stopping a session requires a 3-second circular long press with a progress ring to prevent accidental session loss. While held, the stop button scales up in place with high elevation so the ring remains visible around the user's finger.
- **Device Commands**: Session start sends WalkingPad start, pause sends stop, resume sends start, and stop captures final counters before sending stop.
- **Session Aggregates**: Stored session totals use deltas from the start counters. Calories use a MET estimate based on active duration, body weight, and average speed. HR aggregates come from active readings only. Cadence uses step deltas over elapsed metric time.
- **Live Session Stats**: The session dashboard also displays distance and steps as deltas from the session start counters, matching persisted session totals.
- **Inactive Devices**: Starting/resuming with an inactive watch, stale/missing HR broadcast, or inactive WalkingPad shows a bottom sheet with Pair, Continue, and Never Ask Again options. A connected watch is not considered HR-ready until a positive heart-rate sample has arrived recently.
- **Time Display Caveat**: Current session time behaves like active/moving time because it pauses with the session. If a separate wall-clock elapsed time is needed, add a second stat instead of changing active time semantics.

## 3. Data Strategy
- **Health Connect**: Chosen over Google Fit as the primary health data hub for modern Android devices. We write completed walking sessions, steps, and distance. We read body weight for calorie estimates and steps for normalization. We do not request or write heart-rate records; the watch should remain the source of HR data in Health Connect.
- **Health Connect Permissions**: The profile toggle reflects actual granted permissions. Enabling shows the Health Connect permission flow; disabling opens Health Connect settings because apps cannot revoke Health Connect grants directly. Read/write code paths check only the permissions needed for that operation.
- **Normalized Historical Steps**: Daily Health Connect history uses Health Connect aggregate steps for raw daily totals, then subtracts non-C3P0 step records that overlap completed C3P0 sessions. Record-level counts are prorated across day/session boundaries for the C3P0 and excluded-source breakdown.
- **DataStore**: Used for lightweight persistence like BLE device addresses, unit preferences, inactive-warning preference, backup toggle, profile age, daily step goal, and cached body weight. Room handles the heavy lifting for session history.
- **Unit Source of Truth**: The app's stored unit setting is the source of truth. WalkingPad units are corrected when settings change, when a session starts/resumes/pauses, and when reported device units diverge.
- **Auto Backup**: We rely on Android's native Google Drive Auto Backup to sync Room DB files and DataStore preferences. Backup rules include `c3p0.db`, `c3p0.db-shm`, `c3p0.db-wal`, `datastore/settings.preferences_pb`, and SharedPreferences.
- **Backup Requests**: When Google Drive backup is enabled, the app calls `BackupManager.dataChanged()` on cold start, persisted settings changes, active session progress on a 5-minute throttle, and session completion. This requests Android to schedule a backup; it does not force an immediate upload or restore.
- **Backup Throttle**: Throttled backup requests check the in-memory interval before reading DataStore, and the first request after process start is allowed even when a throttle is supplied.

## 4. UI/UX
- **Jetpack Compose**: Used for all UI. We opted for a simple Dashboard with "Status Dots" to provide immediate feedback on device connectivity.
- **Auto-Speed Algorithm**: Implemented as a Proportional Controller with a Moving Average to ensure treadmill adjustments are smooth and not jittery.
- **Permission Rationale**: Code paths that require Android permissions should show a bottom sheet explaining the request before invoking the system prompt.
- **Session Dashboard Density**: Session stats are compact tiles because the screen now shows distance, steps, normalized steps remaining to goal, active time, calories, current HR, average HR, speed controls, device status, and the HR chart. Steps remaining uses today's normalized Health Connect baseline plus in-progress session steps. Session action controls are centered to avoid the control row feeling lopsided.
- **Heart-Rate Chart**: The session chart includes y-axis labels and zone-colored line segments for Zone 0 through Zone 4.
- **Stats History**: The History screen shows daily Health Connect raw/normalized step history and selected-session details with stored aggregates, metric-derived fallback summaries, and Health Connect normalized step counts when available. The Health Connect daily step section is constrained to roughly a quarter of the page and scrolls internally so other history content remains reachable.

## 5. CI & Distribution
- **Debug APK Workflow**: GitHub Actions builds a debug APK on every push and uploads it as an artifact for remote install testing. Release APKs are deferred until signing keys exist.
- **Artifact Retention**: CI artifacts should use a finite retention period because artifact storage can count against repository/account limits.
