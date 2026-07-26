# MinuteWatcher

MinuteWatcher is an Android application designed to track your mobile carrier's minutes and data usage. It provides a clean UI, highly customizable home screen widgets, and deep integration with Android system features like AppSearch, Quick Settings Tiles, and Dynamic Shortcuts.

## Key Features

- **Dual SIM Support**: Monitor two SIM cards simultaneously with independent limits and reset periods.
- **Customizable Widgets**: 8 different layout modes (horizontal and vertical) with adjustable transparency, colors, and font sizes.
- **Smart Tracking**: Automatically tracks voice calls and mobile data usage using system APIs.
- **Manual Adjustments**: Easily correct usage values if they drift from your carrier's actual data.
- **Semantic Integration**:
    - **AppSearch**: Your usage data is indexed locally for system search discovery.
    - **Quick Settings Tile**: Check your remaining minutes directly from the notification shade.
    - **Dynamic Shortcuts**: Access real-time usage info by long-pressing the app icon.
    - **LocusId Support**: Helps Android understand the context of what you are viewing in the app.
- **Multi-language Support**: Supports 9 languages, including RTL (Arabic) and CJK (Japanese, Chinese).

## Technical Limitations

- **Traffic Tracking**: Android provides aggregate cellular traffic for the entire device rather than per-SIM. MinuteWatcher attributes all detected mobile traffic to the SIM card currently set as the "Default Data SIM" in system settings. If you switch your default data SIM, the app resets its internal baseline to start tracking for the new SIM.

## Technical Architecture

The app follows modern Android development best practices:
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **DI**: Hilt
- **Background Work**: WorkManager
- **Data Storage**: DataStore (for usage data and UI settings).
- **Architecture Layers**:
    - `contracts`: SDK-agnostic data models and enums.
    - `semantic`: A centralized layer that manages a "Global Snapshot" of the app's state.
    - `domain`: UseCases for business logic.
    - `ui`: MVVM-based UI components.
    - `service`: Background trackers and system integrations.

## Security

MinuteWatcher takes privacy seriously. Sensitive usage data and limits are stored locally on your device. No call history or personal data is ever uploaded to any server.

## Building the Project

1. Clone the repository.
2. Open in Android Studio (Narwhal or newer recommended).
3. Ensure you have the latest Android SDK and Build Tools installed.
4. Build and run using the `app` module.

## License

MIT License
