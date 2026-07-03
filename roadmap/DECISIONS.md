# Architectural Decisions & Rationale

## 1. Bluetooth (BLE) Architecture
- **Shared Controller**: We use a modular BLE architecture where `BleConnection` handles the low-level GATT logic and `TreadmillManager` / `HeartRateManager` handle the high-level protocol parsing.
- **Mocks**: Every manager has a mock implementation (`MockTreadmillManager`) to allow UI and logic development without physical hardware.

## 2. Session Management
- **Foreground Service**: Sessions run in `SessionService` to prevent the OS from killing the app during long workouts. This is required for reliable BLE data collection and Health Connect exports.
- **Persistence**: Real-time metrics (HR, Speed, Cadence) are saved to Room as they arrive, ensuring data isn't lost if the app crashes.

## 3. Data Strategy
- **Health Connect**: Chosen over Google Fit as the primary health data hub for modern Android devices. We handle "Normalized Steps" by filtering out overlapping data from other sources (like watches) during C3P0 sessions.
- **DataStore**: Used for lightweight persistence like BLE device addresses and user preferences (Age, Goals). Room handles the heavy lifting for session history.
- **Auto Backup**: We rely on Android's native Google Drive backup to sync the Room DB and DataStore files. This avoids the need for a custom backend.

## 4. UI/UX
- **Jetpack Compose**: Used for all UI. We opted for a simple Dashboard with "Status Dots" to provide immediate feedback on device connectivity.
- **Auto-Speed Algorithm**: Implemented as a Proportional Controller with a Moving Average to ensure treadmill adjustments are smooth and not jittery.
