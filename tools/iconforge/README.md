# IconForge Tooling

Tooling resources for operator vector icon generation and reference asset management.

## Components

- `simple_icons.json`: Simple Icons catalog (version 16.35.0, CC0-1.0).
- `simple_icons_version.txt`: Version metadata for Simple Icons catalog.
- `cache/liberation_sans_bold_glyphs.json`: Cached glyph outlines for Liberation Sans Bold typeface (SIL OFL 1.1).
- `out/*.xml`: Verbatim copies of canonical vector assets from `core/resources/src/main/res/drawable`, intended solely for reference comparison when creating new icons.

The canonical list of operator brands is defined in `OperatorBrandDatabase.operatorResourceMap` and the vector assets in `core/resources/src/main/res/drawable`.

## Maintenance Rules

- When a canonical asset in `core/resources/src/main/res/drawable/` that has a reference copy in `out/` is modified, its copy in `out/` must be updated in the same change.
- When a canonical asset is deleted, its reference copy in `out/` must also be deleted.

## Creating and Validating Icons

When creating new operator vector drawables:
1. Base the vector structure on an existing template (e.g. `ic_operator_mts.xml`).
2. Source brand glyphs from CC0 Simple Icons or Liberation Sans Bold glyph cache.
3. Validate vector compliance, geometry, and uniqueness via the test suite:
   ```powershell
   $env:ANDROID_PREFS_ROOT=$null; ./gradlew :core:resources:testDebugUnitTest :core:android:testDebugUnitTest
   ```
