# Localization Guidelines

This document outlines the best practices and requirements for localizing the MinuteWatcher application.

## Plural Rules

Different languages have different rules for pluralizing nouns based on quantity. Android uses the CLDR (Common Locale Data Repository) plural rules.

### Standard Requirements

For languages with non-standard plural rules, ensure that the full set of required quantities is provided in `strings.xml`.

- **Arabic (`ar`)**: `zero`, `one`, `two`, `few`, `many`, `other`.
- **Russian (`ru`)**: `one`, `few`, `many`, `other`.
- **Spanish (`es`)**: `one`, `many`, `other`.
- **French (`fr`)**: `one`, `many`, `other`.

> [!IMPORTANT]
> Always use the full set of quantities according to CLDR/Lint requirements. Missing any quantity can cause Lint fatal errors or incorrect display at runtime.

### Heuristics and Quality

- **Multiples of 1,000,000**: In languages like Spanish and French, the `many` quantity is used for large numbers (e.g., exactly 1,000,000). Even if the application currently deals with smaller numbers (e.g., minutes in a month), providing these quantities ensures compliance with Android Lint and future-proofs the translations.
- **Verification**: Run `./gradlew lintDebug` to verify that all required quantities are present and no `MissingTranslation` or `PluralsCandidate` issues exist.
