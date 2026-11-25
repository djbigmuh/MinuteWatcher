# MinuteWatcher Compose design system

This module is the shared source of truth for Compose UI styling.

## Theme

Wrap app and feature content in `MinuteWatcherTheme`. The app entry points already do this.
Use semantic Material 3 values in feature code:

- `MaterialTheme.colorScheme.primary` for primary actions and emphasis
- `MaterialTheme.colorScheme.surfaceContainerLow` for grouped cards
- `MaterialTheme.colorScheme.onSurface` and `onSurfaceVariant` for text hierarchy
- `MaterialTheme.typography` for all text styles
- `MaterialTheme.shapes` for the shared Material 3 shape scale

Avoid copying palette hex values into feature modules.

## Components

Prefer the shared components in `component` over styling Material components locally:

- `AppCard`, `AppDialog`, `AppDropdownMenu`
- `AppButton`, `AppOutlinedButton`, `AppTextButton`, `AppExtendedFab`
- `AppTextField`, `AppTextFieldValue`
- `AppCheckbox`, `AppCheckboxRow`, `AppSettingLabel`, `AppSwitch`, `AppTopBar`
- `AppSectionHeader`

These components keep shape, surface, typography and state colors consistent while preserving normal Compose callbacks and slots.

## Layout tokens

Use `MwSpacing` and `MwShape` for common spacing and corner radii. A feature may still define a local measurement when it is genuinely specific to that feature, but repeated values belong here.

## Adding a new component

1. Add the reusable composable in this module.
2. Derive colors and text styles from `MaterialTheme`.
3. Use `MwSpacing` and `MwShape` rather than introducing another set of arbitrary constants.
4. Expose state and callbacks as normal Compose parameters; do not hide business logic in design components.
5. Add a preview when the component has meaningful visual states.
