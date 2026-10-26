# UI Guidelines and Launcher Icon Specifications

## App Icon Layout

Canonical launcher icon layout must be maintained:

- Adaptive icon XML: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Launcher icon files are placed only in `res/mipmap-anydpi-v26/` (adaptive icon) and `res/drawable/` (vector parts);
  placing the adaptive icon in unqualified `res/mipmap/` is prohibited — explicit qualifier `anydpi-v26` guarantees
  priority when resolving resources on device.
- Required adaptive icon elements: `<background>`, `<foreground>`, `<monochrome>`
- Vector parts: `app/src/main/res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`
- Namespace MUST be `http://schemas.android.com/apk/res/android`
- Do not move, duplicate, or rasterize launcher icon files.
- Do not create `ic_launcher_round` without a matching manifest reference in the same change, nor suppress lint icon checks.
- Manifest `android:roundIcon` references `@mipmap/ic_launcher`.
