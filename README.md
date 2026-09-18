# MinuteWatcher

MinuteWatcher is an Android application designed to track your mobile carrier's minutes and data usage. It provides a clean UI, highly customizable home screen widgets, and deep integration with Android system features like AppSearch, Quick Settings Tiles, and Dynamic Shortcuts.

## Key Features

- **Multi-SIM Support**: Architecture supports up to 10 slots (used in benchmark mode). Real-world devices with up to 3 SIMs are supported with independent limits and optimized widget layouts.
- **Customizable Widgets**: Multiple layout modes (including high-detail "Six Lines") with adjustable transparency, colors, and font sizes.
- **Smart Tracking**: Automatically tracks voice calls and mobile data usage using system APIs.
- **Manual Adjustments**: Easily correct usage values if they drift from your carrier's actual data.
- **Privacy First**: All data is stored locally. No third-party tracking or cloud uploads.
- **Multi-language Support**: Supports 8+ languages, including RTL (Arabic) and CJK (Japanese, Chinese).

## Technical Limitations

### Traffic Tracking
Android provides mobile data usage as an aggregate value for the entire device rather than per-SIM. MinuteWatcher attributes all detected mobile traffic to the SIM card currently selected as the **"Default Data SIM"** in your system settings.
- Switching the default data SIM triggers an internal baseline reset to ensure traffic is correctly attributed to the new active SIM from that moment onward.
- Data consumed over Wi-Fi is ignored.

### Call Tracking
Call duration is tracked using real-time system state changes.
- The app does **not** read your call log history.
- Tracking begins when a call enters the "Off-hook" state and ends when the device returns to "Idle".
- **Dual-SIM Attribution**: If an "end call" event lacks SIM information (subId = -1), MinuteWatcher attributes it to
  the only active session. If all calls are ended simultaneously (global IDLE state), all tracked sessions are closed
  automatically. If multiple sessions remain active and cannot be distinguished, an error banner is displayed instead of
  guessing. This limitation is mostly resolved on Android 12+ where per-SIM call states are available.
- **Rounding Logic**: Call duration is rounded **up** to the nearest minute (e.g., a 1-second call adds 1 minute to usage, a 61-second call adds 2 minutes). This aligns with common mobile carrier billing policies.

### AppSearch Integration
Semantic search (AppFunctions) is automatically disabled on devices without the AppSearch system service (e.g., LineageOS or CalyxOS without Google Services).
- An error banner is **not** displayed for `NOT_SUPPORTED` errors to ensure a clean UX on these devices.
- For other recoverable issues (like database schema drift), the app attempts automatic recovery with limited retry attempts.

## Permissions

To provide accurate tracking, MinuteWatcher requires the following permissions:
- **`READ_PHONE_STATE`**: Used to identify SIM cards (via Subscription ID) and detect active call states.
- **`PACKAGE_USAGE_STATS`** (Usage Access): Required to query the system for mobile data consumption.
- **`POST_NOTIFICATIONS`**: Used to send low-balance alerts for minutes and traffic.
- **`RECEIVE_BOOT_COMPLETED`**: Allows the app to resume monitoring services automatically after a device reboot.

## Privacy & Security

MinuteWatcher is built with a "Local First" philosophy:
- **Zero Server Communication**: Your usage data, limits, and settings never leave your device.
- **No Analytics**: We do not use Firebase, Google Analytics, or any other tracking SDKs.
- **No Identity Storage**: SIM cards are identified by stable internal IDs; personal phone numbers or contact names are never accessed.
- **Backup**: Cloud backup is disabled for application data (DataStore and SharedPreferences) to protect your privacy. On Android 12 (API 31) and higher, only explicit device-to-device transfer is permitted. On older versions (API 26–30), application data is excluded from Auto Backup.

## Technical Architecture

The app follows modern Android development best practices:
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **DI**: Hilt
- **Background Work**: WorkManager
- **Data Storage**: DataStore (for usage data and UI settings).
- **Static Analysis**: Detekt and Ktlint enforced (locally via `verify.ps1`; GitHub CI is not currently used).

### Non-Stable Dependencies
The project uses a few non-stable (Beta/Alpha) versions of critical libraries to access essential fixes and modern features:
- **Baseline Profile (Beta)**: Used to resolve critical performance profiling issues on newer Android versions.
- **Robolectric (Beta)**: Used to ensure compatibility with the latest Jetpack Compose features in unit tests.


## Development Requirements

- **JDK**: JDK 21 (Temurin 21.0.12+ recommended).
- **Environment**: JDK 25 and other versions should not be in the system `PATH` to avoid leakage into child processes (Detekt/KSP).
- **Gradle**: Configured via local environment or user-level settings for cross-platform compatibility.

## License

MIT License
