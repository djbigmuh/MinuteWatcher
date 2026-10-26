# Operator brand assets — inventory

All operator icons (`core/resources/src/main/res/drawable/ic_operator_*.xml`) are vector drawables in a unified 48x48 viewport. Every icon is validated by `OperatorIconAssetComplianceTest`, `OperatorVectorIntegrityTest` and `OperatorIconGeometryTest`.

## Category A — CC0 brand glyphs (simple-icons)

The following 13 icons embed brand glyph paths derived from the simple-icons project (https://simple-icons.org, version 16.35.0), licensed CC0-1.0:

| Resource             | simple-icons slug |
|----------------------|-------------------|
| ic_operator_airtel   | airtel            |
| ic_operator_att      | atandt            |
| ic_operator_jio      | jio               |
| ic_operator_magenta  | deutschetelekom   |
| ic_operator_movistar | movistar          |
| ic_operator_o2       | o2                |
| ic_operator_odido    | odido             |
| ic_operator_orange   | orange            |
| ic_operator_sunrise  | sunrise           |
| ic_operator_t_mobile | deutschetelekom   |
| ic_operator_telekom  | deutschetelekom   |
| ic_operator_telenor  | telenor           |
| ic_operator_vodafone | vodafone          |

## Category B — original stylized marks

All remaining 515 `ic_operator_*.xml` files are original stylized marks created for this project: a light or dark octagon plate carrying a brand-colored glyph (simplified brand symbol or brand initials). Glyph outlines for initial-based marks are derived from the Liberation Sans Bold typeface (SIL Open Font License 1.1). These marks are not official logos; they identify operators by brand color and initials under nominative use.

## Category C — retained legacy marks

The following 5 icons that already contained recognizable brand-colored artwork prior to normalization are retained unchanged:
- `ic_operator_verizon`
- `ic_operator_rakuten`
- `ic_operator_yettel`
- `ic_operator_yoigo`
- `ic_operator_vi`

## License summary

- CC0-1.0: 13 glyph sources (Category A).
- SIL Open Font License 1.1: Liberation Sans Bold (glyph outlines, Category B; text of license in `core/resources/src/main/assets/OFL-1.1.txt`).
- Original artwork: Category B and Category C.

## Notes

- Trademark rights remain with their owners; icons are used for nominative operator identification.
- Brand colors are adjusted where required to satisfy the contrast rule of the compliance test (WCAG 2.1 ratio >= 3.0 against white, gray and dark backgrounds).

## Uniqueness rules

1. Normalized contents of icon files for different brands are unique.
2. Normalized glyph geometry for different brands is unique, except documented shared corporate mark groups.
3. A brand marker cannot be a solid rectangle or rectangular ring.
4. Rules are enforced by `OperatorIconUniquenessTest`.

## Shared corporate marks

- **Deutsche Telekom Group**: `ic_operator_t_mobile`, `ic_operator_telekom`, `ic_operator_magenta` share the corporate T-Mobile/Telekom glyph mark.
- **Turkcell Group**: `ic_operator_life_by`, `ic_operator_lifecell` share the corporate life:) / lifecell smiley mark.
- **Monaco Telecom Group**: `ic_operator_epic_cy` is used for Epic (Cyprus) and Epic (Malta, PLMN 27801).

## Normative asset compliance rules

All operator vector icons in the repository (`ic_operator_*.xml`) are validated by unit tests
in [OperatorIconAssetComplianceTest.kt](../core/resources/src/test/java/host/serv/djbigmuh/minutewatcher/core/resources/OperatorIconAssetComplianceTest.kt)
against the following 13 normative rules:

1. `viewportWidth` and `viewportHeight` must equal `48`.
2. Explicit `android:width` and `android:height` must equal `48dp`.
3. Root `<vector>` element must not contain any `<group>` child elements (all path transformations unwrapped).
4. No text glyphs or text elements allowed in vector graphics.
5. All color attributes must use explicit 8-digit hex format `#AARRGGBB` or linear/radial gradient definitions.
6. Path coordinate numbers must be rounded to at most 2 decimal places.
7. Total `android:pathData` length must not exceed 8,000 bytes per drawable file.
8. Bounding box width must occupy 60%..92% of the viewport, and bounding box height 40%..92% (or >= 25% for wide icons).
9. Center of graphic bounding box must be centered at `(24, 24) +/- 4`.
10. All path coordinates must fit strictly within `[0, 48] x [0, 48]`.
11. Total file size must not exceed 24 KB.
12. A filled path must specify `android:fillType="evenOdd"` when its `android:pathData` contains two or more closed subpaths and the bounding rectangle of one closed subpath is strictly contained within the bounding rectangle of another closed subpath. Paths without nested closed subpaths are not required to declare `fillType`. The requirement is enforced by `OperatorIconAssetComplianceTest`.
13. Icon must pass automated contrast validation (WCAG 2.1 contrast ratio >= 3.0 on backgrounds `#FFFFFFFF`,
    `#FF808080`, and `#FF141218` for at least 2% of rasterized pixels at 96x96 resolution).
