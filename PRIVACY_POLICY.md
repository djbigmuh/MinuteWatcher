# Privacy Policy

**Last Updated: October 2026**

MinuteWatcher ("the App") is committed to protecting your privacy. This policy explains how we handle your data.

## 1. Data Collection and Usage
MinuteWatcher tracks your mobile carrier's minutes and data usage locally on your device.

*   **Call Data:** The App monitors the "Off-hook" and "Idle" states of your device to calculate the duration of outgoing and incoming calls. It does **not** read your call history, logs, or contact list. If a call-end event does not include SIM information, the App attributes it to the only active call session. If all calls have ended, all tracked sessions are closed. If multiple sessions remain active and cannot be distinguished, the App displays an in-app error message instead of guessing. This ambiguity is largely resolved on Android 12 and newer, where per-SIM call state is available through documented system APIs.
*   **Traffic Data:** The App queries system statistics for mobile data consumption. This data is used solely to display remaining balances against your configured limits.
*   **SIM Identification:** The App uses stable system-provided IDs to distinguish between multiple SIM cards. It does **not** access your phone number.
*   **Always-on Display & Lock Screen Summary:** If enabled, a persistent silent notification displays your remaining minutes and traffic on the lock screen and (on supported devices) the Always-on Display. Because this summary is visible on the lock screen, it may be seen by people near your device. This feature is disabled by default and can be toggled on or off in settings at any time. All summary data remains strictly on your device.

## 2. Data Storage
All data processed by the App, including usage statistics, limits, and UI preferences, is stored **locally on your device** using Android DataStore.

*   We do **not** transmit any data to external servers.
*   We do **not** use third-party analytics (e.g., Firebase, Google Analytics).
*   The App does not require a user account or any personally identifiable information (PII).

## 3. Permissions
The App requests the following permissions to function:

*   **Phone State (`READ_PHONE_STATE`):** Necessary to identify SIM cards and track the timing of calls.
*   **Usage Access (`PACKAGE_USAGE_STATS`):** Necessary to retrieve mobile data consumption from the system.
*   **Notifications (`POST_NOTIFICATIONS`):** Used to send low-balance alerts and, if enabled by you, an optional persistent summary notification on the lock screen and Always-on Display.
*   **Boot Completed (`RECEIVE_BOOT_COMPLETED`):** Used to ensure tracking resumes automatically after you restart your device.

## 4. Data Backup
MinuteWatcher does **not** perform cloud backups of your usage data, configuration, or SIM identifiers to protect your privacy. Usage statistics and limits remain solely on your device.

When you explicitly perform a "Device-to-Device Transfer" (e.g., when setting up a new Android phone from an old one via cable or direct Wi-Fi), your local data is transferred by the Android system to ensure continuity of your tracking.

## 5. Changes to This Policy
We may update our Privacy Policy from time to time. Any changes will be reflected in the updated text within the App's repository.

## 6. Contact
If you have any questions about this Privacy Policy, please contact the repository maintainers.
