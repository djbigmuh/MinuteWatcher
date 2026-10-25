# Store Assets Generator

Python tool for generating store metadata images for Google Play and F-Droid listings from canonical application vector drawables.

## Generated Assets

- `fastlane/metadata/android/en-US/images/icon.png` (512x512 px PNG)
- `fastlane/metadata/android/en-US/images/featureGraphic.png` (1024x500 px PNG)

## Vector Sources

- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`

## Dependencies & Execution

The generator uses Python 3 and the standard `Pillow` library for path rasterization and composition. No registration-based or cloud conversion services are required.

To regenerate store images:

```powershell
python tools/store_assets/generate_store_assets.py
```
