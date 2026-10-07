# Agents Information

This project is assisted by AI Agents.

## AppSearch Note

Semantic search indexing uses bundled LocalStorage. In case of runtime indexing failures on a device, the status is set
to `NOT_SUPPORTED`. An error banner is not displayed for errors of type `NOT_SUPPORTED`.

## Always-on Display Note

The Always-on Display summary feature is implemented solely as a persistent silent ongoing notification via documented system APIs (`NotificationManager`, channel with `IMPORTANCE_MIN`).
- Prohibited for this feature: reading undocumented settings (including `Settings.Secure`), using OEM-specific APIs, using `Notification.ProgressStyle`/Live Updates.
- Ambient display rendering is determined entirely by the device OS and OEM firmware; firmware deviations from Google documentation are not considered app defects.
- Availability checks rely exclusively on: `POST_NOTIFICATIONS` permission, `NotificationManagerCompat.areNotificationsEnabled()`, and the summary notification channel importance.

## Testing Strategy

The project follows a comprehensive testing strategy documented in [docs/testing.md](docs/testing.md).

## App Icon Note

Canonical launcher icon layout must be maintained:

- Adaptive icon XML: `app/src/main/res/mipmap/ic_launcher.xml`
- Required adaptive icon elements: `<background>`, `<foreground>`, `<monochrome>`
- Vector parts: `app/src/main/res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`
- Namespace MUST be `http://schemas.android.com/apk/res/android`
- Do not move, duplicate, or rasterize launcher icon files.
- Do not create `ic_launcher_round` without a matching manifest reference in the same change, nor suppress lint icon checks.
- Manifest `android:roundIcon` references `@mipmap/ic_launcher`.
