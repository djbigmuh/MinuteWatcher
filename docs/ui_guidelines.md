# UI Guidelines

This document outlines the UI and UX standards for the MinuteWatcher application.

## RTL (Right-to-Left) Support

To ensure a high-quality experience for users of RTL languages (like Arabic), follow these guidelines for icons and layouts.

### Icon Mirroring

- **Directional Icons**: Icons that convey direction (e.g., arrows, chevrons, progress indicators) must be mirrored in RTL mode. In Compose, use `Icons.AutoMirrored` or set `autoMirror = true` in `Image` or `Icon` components.
- **Logos and Brands**: Brand logos (e.g., mobile operator icons) must **not** be mirrored. They should maintain their original orientation regardless of the layout direction.
- **Static Icons**: Icons without intrinsic directionality (e.g., Settings gear, Language globe, Close 'X') do not require mirroring.

### Summary Table

| Icon Type | Mirror in RTL? | Implementation Note |
| :--- | :--- | :--- |
| Arrows/Chevrons | Yes | Use `Icons.AutoMirrored` |
| Operator Logos | No | Default behavior (`autoMirror = false`) |
| Close/Cancel | No | Symmetry usually makes mirroring unnecessary |
| Settings/Palette | No | Fixed semantic meaning |

> [!NOTE]
> Icons with semantic directionality (arrows, chevron) — autoMirror=true. Logos — false.
