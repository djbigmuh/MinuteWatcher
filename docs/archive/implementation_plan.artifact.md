# [ARCHIVE] SIM Identity/Discovery Refactoring Plan

*This is an historical artifact archived from the project root.*

This plan aims to align the SIM identity and discovery logic with documented Android API guarantees, ensure stable internal identifiers, and provide explicit discovery result states.

## User Review Required

> [!IMPORTANT]
> Since `ICCID` is restricted on Android 10+ for non-system apps, we cannot guarantee a truly unique SIM ID that survives device resets or app reinstalls if the OS changes the `subscriptionId`.
> We will use a mapping strategy to track known SIMs, but for SIMs where ICCID is missing, the identity will be marked as `UNSTABLE`.

## Proposed Changes

### [core:model]

#### [MODIFY] [SimIdentity.kt](../../core/model/src/main/java/com/example/minutewatcher/core/model/SimIdentity.kt)
- Add `isStable: Boolean` property.
- Ensure `IdentitySource` covers all cases.

#### [NEW] [SimDiscoveryResult.kt](../../core/model/src/main/java/com/example/minutewatcher/core/model/SimDiscoveryResult.kt)
- Define a sealed interface for discovery results: `Available`, `PermissionMissing`, `Unsupported`, `Failed`.

### [core:data]

#### [MODIFY] [SimIdentityDiscovery.kt](../../core/data/src/main/java/com/example/minutewatcher/core/data/SimIdentityDiscovery.kt)
- Change return types to `SimDiscoveryResult` where appropriate.
- Ensure only documented APIs are used.

#### [MODIFY] [ActiveSimsFetcher.kt](../../core/data/src/main/java/com/example/minutewatcher/core/data/ActiveSimsFetcher.kt)
- Update to handle the new `SimDiscoveryResult` model.

#### [MODIFY] [SimIdentityObserver.kt](../../core/data/src/main/java/com/example/minutewatcher/core/data/SimIdentityObserver.kt)
- Explicitly separate Mock SIM logic from production discovery.
- Use `SimDiscoveryResult` for internal state.

#### [MODIFY] [SimIdentityUtils.kt](../../core/data/src/main/java/com/example/minutewatcher/core/data/util/SimIdentityUtils.kt)
- Refactor `createIdentityFromInfo` to generate stable IDs based on app internal logic.
- Differentiate between `ICCID` (Stable) and `SUBSCRIPTION_ID`/`SLOT` (Unstable).

### [Regression Tests]

#### [NEW] [SimDiscoveryRegressionTest.kt](../../core/data/src/test/java/com/example/minutewatcher/core/data/SimDiscoveryRegressionTest.kt)
- Implement all 11 required test scenarios.

## Verification Plan

### Automated Tests
- Run `SimDiscoveryRegressionTest`.
- Run existing tests to ensure no regressions: `SimIdentityObserverTest`, `IdentityMigrationServiceTest`.
- Command: `./gradlew :core:data:test`

### Manual Verification
- Deploy to a device with two SIMs and verify correct identification.
- Toggle Airplane mode / SIM settings to verify discovery updates.
- Verify Mock mode via Debug settings (if applicable).
