# Implementation Plan - C3P0 Companion App

This plan outlines the development of C3P0, an Android companion app for WalkingPad C2 and Garmin Venu 3, focusing on BLE control, Health Connect integration, and cloud sync.

## User Requirements & Feedback

- **Auto-Speed Algorithm**: Uses a smooth adjustment strategy (0 to 0.5 km/h changes) every 30-60 seconds. The goal is to reach the target Heart Rate (Zone 2) within 5 minutes using a polynomial curve or moving average approach to avoid jitter.
- **Heart Rate**: Auto-calculate Max HR (220-age) with manual override in Profile.
- **Cloud Sync**: Utilize Android's **Auto Backup** feature for Google Drive, with an explicit sign-in/settings flow if needed for extra granularity.
- **Normalization**: Normalized Steps = Total Steps - Overlapping Non-C3P0 Steps (from Health Connect).
- **Session Metrics**: Capture and persist comprehensive metrics for each session:
    - Time-series Heart Rate (HR) data.
    - Time-series Speed data.
    - Aggregate and per-minute Steps, Distance, and Cadence (Steps per Minute).
    - Session duration and Energy (Calories) burned.
    - All metrics should be viewable in historical session details.

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
- **Android Auto Backup**: Configured via `backup_rules.xml` to sync the Room DB and SharedPreferences to Google Drive.

### 4. UI Layer (Jetpack Compose)
- **`SessionDashboard`**: Mirroring the provided screenshots (Distance, Steps, Time, Energy).
- **`History & Stats`**: Charts (Vico) showing both raw and normalized steps.

## Verification Plan

### Automated Tests
- `WalkingPadProtocolTest`: Validate CRC and status packet parsing.
- `Zone2AlgorithmTest`: Simulate HR drifting and verify speed adjustment curve over a 5-minute window.
- `StepNormalizationTest`: Mock overlapping Health Connect records and verify the exclusion logic.

### Manual Verification
- **Health Connect Toolbox**: Use to simulate external step counts.
- **BLE Emulation**: Use an emulator or second device to act as the Treadmill/Watch.
