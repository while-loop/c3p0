# Implementation Plan - C3P0 Companion App

This plan outlines the development of C3P0, an Android companion app for WalkingPad C2 and Garmin Venu 3, focusing on BLE control, Health Connect integration, and cloud sync.

## User Requirements & Feedback

- **Auto-Speed Algorithm**: Uses a smooth adjustment strategy (0 to 0.5 km/h changes) every 30-60 seconds. The goal is to reach the target Heart Rate (Zone 2) within 5 minutes using a polynomial curve or moving average approach to avoid jitter.
- **Heart Rate**: Auto-calculate Max HR (220-age) with manual override in Profile.
- **Cloud Sync**: Utilize Android's **Auto Backup** feature for Google Drive. The in-app Google Drive toggle persists user intent and requests Android backups, but Android controls actual upload/restore timing.
- **Normalization**: Normalized Steps = Total Steps - Overlapping Non-C3P0 Steps (from Health Connect).
- **Session Metrics**: Capture and persist comprehensive metrics for each session:
    - Time-series Heart Rate (HR) data.
    - Time-series Speed data.
    - Aggregate and per-minute Steps, Distance, and Cadence (Steps per Minute).
    - Session duration and Energy (Calories) burned.
    - All metrics should be viewable in historical session details.
- **Session Controls**: Provide start, pause/resume, and stop. Stop requires a 3-second circular long press with a progress ring.
- **Permission UX**: Show explanatory bottom sheets before permission prompts and before continuing without active devices.
- **Install Testing**: CI builds debug APK artifacts on every push so the app can be installed remotely during development.

## Proposed Architecture

### 1. Bluetooth (BLE) Layer
- **`BleController`**: Shared singleton for device discovery and GATT connection management.
- **`WalkingPadManager`**: Reverse-engineered protocol (Fixed prefix `0xF8`, suffix `0xFD`, CRC checksum).
- **`GarminManager`**: Standard Heart Rate Service (`0x180D`) with `Body Sensor Location` characteristic to ensure compatibility.

### 2. Domain & Algorithm Layer
- **`Zone2Controller`**: Implementation of the "smooth curve" algorithm.
    - Uses a moving average of HR samples to filter noise.
    - Calculates speed adjustments based on distance from target HR.
- **`StepNormalizationUseCase`**: Queries Health Connect for records during the session window and filters by `DataOrigin`.

### 3. Data Layer
- **Room DB**: Stores `Session` metadata and a related `SessionMetrics` table for time-series data (HR, Speed, Cadence).
- **DataStore**: Stores saved devices, unit system, backup toggle, inactive-device warning preference, and cached Health Connect weight.
- **Android Auto Backup**: Configured via `backup_rules.xml` and `data_extraction_rules.xml` to sync Room DB files, DataStore preferences, and SharedPreferences to Google Drive/device transfer.
- **Backup Triggers**: Call `BackupManager.dataChanged()` when enabled on cold start, persisted settings changes, session progress with a throttle, and session completion.
- **Health Connect**: Write completed sessions, steps, and distance. Read weight for calorie estimates. Do not write HR records because watches should own HR data.

### 4. UI Layer (Jetpack Compose)
- **`SessionDashboard`**: Mirroring the provided screenshots (Distance, Steps, Time, Energy).
- **Session Stats**: Show active time, calories, current HR, session average HR, and a zone-colored HR history chart with y-axis labels.
- **Units**: Default to imperial and keep the WalkingPad unit setting synchronized with app storage.
- **Pairing/Profile**: Deduplicate BLE devices, sort by RSSI closest-first, and show connected/selected device status.
- **`History & Stats`**: Charts (Vico) showing both raw and normalized steps.

### 5. CI
- **GitHub Actions**: Build the debug APK on every push and upload it as a retained artifact. Release APK builds remain blocked until signing keys are created.

## Verification Plan

### Automated Tests
- `WalkingPadProtocolTest`: Validate CRC and status packet parsing.
- `Zone2AlgorithmTest`: Simulate HR drifting and verify speed adjustment curve over a 5-minute window.
- `StepNormalizationTest`: Mock overlapping Health Connect records and verify the exclusion logic.

### Manual Verification
- **Health Connect Toolbox**: Use to simulate external step counts.
- **BLE Emulation**: Use an emulator or second device to act as the Treadmill/Watch.
- **Android Backup**: Verify backup/restore on a real device with Google backup enabled. Auto Backup is asynchronous, so use Android backup tooling or allow time for the OS-scheduled backup.
- **APK Artifact Install**: Install the debug APK from the GitHub Actions artifact after each push when validating remotely.
