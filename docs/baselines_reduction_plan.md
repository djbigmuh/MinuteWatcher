# Baselines Reduction Plan

This document outlines the strategy for reducing and eventually eliminating issues captured in `lint-baseline.xml` and `detekt-baseline.xml` files.

## Goal
The ultimate goal is to have zero issues in baselines, ensuring that all new code adheres to project quality standards and existing technical debt is gradually cleared.

## Strategy

### 1. Stricter "No Growth" Policy
- **Zero Tolerance in CI**: Detekt and Lint checks in CI must use existing baselines. Regeneration of baselines (`detektBaseline`, `lintUpdateBaseline`) is strictly prohibited in CI environments.
- **Fail on New Issues**: Any issue not present in the current baseline will cause the build to fail.
- **Merge Blockers**: Pull requests that increase the number of issues in a baseline file should not be merged.

### 2. Gradual Cleanup
- **Legacy Only**: Baselines must ONLY capture pre-existing issues. Adding issues from new files to any baseline is strictly prohibited.
- **Refactoring Requirement**: When refactoring a file, developers MUST first remove all its current entries from the baseline, then fix the resulting issues at the source.
  - **Правило**: При рефакторинге файла сначала удалить его entries из baseline, затем исправить issues.
- **Continuous Fixing**: When modifying existing code, developers are encouraged to fix any baseline issues in the affected files.

### 3. Baseline Refinement
- Baselines will be regenerated periodically (e.g., once a month) to remove issues that have been fixed but are still present in the baseline XML.
- `detektBaseline` and `lint` tasks should be run with flags to update baselines when appropriate.

## Monitoring
- Track the number of issues in each baseline file over time.
- Report progress during sprint reviews or technical planning meetings.
