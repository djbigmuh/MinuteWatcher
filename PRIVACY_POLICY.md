# Privacy Policy

**Last Updated: July 2026**

MinuteWatcher ("the App") is committed to protecting your privacy. This policy explains how we handle your data.

## 1. Data Collection and Usage
MinuteWatcher tracks your mobile carrier's minutes and data usage locally on your device.

*   **Call Data:** The App monitors the "Off-hook" and "Idle" states of your device to calculate the duration of outgoing and incoming calls. It does **not** read your call history, logs, or contact list. On older Android versions (API 30 and below) with multiple active SIMs, the App may use heuristic methods to attribute call completion events to the correct SIM due to system limitations.
*   **Traffic Data:** The App queries system statistics for mobile data consumption. This data is used solely to display remaining balances against your configured limits.
*   **SIM Identification:** The App uses stable system-provided IDs to distinguish between multiple SIM cards. It does **not** access your phone number.

## 2. Data Storage
All data processed by the App, including usage statistics, limits, and UI preferences, is stored **locally on your device** using Android DataStore.

*   We do **not** transmit any data to external servers.
*   We do **not** use third-party analytics (e.g., Firebase, Google Analytics).
*   The App does not require a user account or any personally identifiable information (PII).

## 3. Permissions
The App requests the following permissions to function:

*   **Phone State (`READ_PHONE_STATE`):** Necessary to identify SIM cards and track the timing of calls.
*   **Usage Access (`PACKAGE_USAGE_STATS`):** Necessary to retrieve mobile data consumption from the system.
*   **Notifications (`POST_NOTIFICATIONS`):** Used only to alert you when your minutes or traffic balances are low.
*   **Boot Completed (`RECEIVE_BOOT_COMPLETED`):** Used to ensure tracking resumes automatically after you restart your device.

## 4. Data Backup
MinuteWatcher does **not** perform cloud backups of your usage data, configuration, or SIM identifiers to protect your privacy. Usage statistics and limits remain solely on your device.

When you explicitly perform a "Device-to-Device Transfer" (e.g., when setting up a new Android phone from an old one via cable or direct Wi-Fi), your local data is transferred by the Android system to ensure continuity of your tracking.

## 5. Changes to This Policy
We may update our Privacy Policy from time to time. Any changes will be reflected in the updated text within the App's repository.

## 6. Contact
If you have any questions about this Privacy Policy, please contact the repository maintainers.
