# Baselines Reduction Plan

This document outlines the strategy for reducing and eventually eliminating issues captured in `lint-baseline.xml` and `detekt-baseline.xml` files.

## Goal
The ultimate goal is to have zero issues in baselines, ensuring that all new code adheres to project quality standards and existing technical debt is gradually cleared.

## Strategy

### 1. "No New Issues" Policy
- All new code must pass Lint and Detekt checks without adding new entries to the baseline.
- CI/CD should be configured to fail if new issues are introduced.

### 2. Gradual Cleanup
- When modifying existing code, developers are encouraged to fix any baseline issues in the affected files.
- Dedicated "cleanup" tasks will be scheduled periodically to address high-priority or easily fixable issues.

### 3. Baseline Refinement
- Baselines will be regenerated periodically (e.g., once a month) to remove issues that have been fixed but are still present in the baseline XML.
- `detektBaseline` and `lint` tasks should be run with flags to update baselines when appropriate.

## Monitoring
- Track the number of issues in each baseline file over time.
- Report progress during sprint reviews or technical planning meetings.
