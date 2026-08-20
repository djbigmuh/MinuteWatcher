# Testing Guidelines

This document outlines the testing strategy and environment requirements for the MinuteWatcher project.

## JVM Arguments for Unit Tests

The following `jvmArgs` are configured in the root `build.gradle.kts` for the `Test` tasks. These are required for stability across different JDK versions (specifically JDK 17 and 21) and for libraries like Robolectric and Mockito.

### Required `add-opens` for Robolectric

Robolectric uses internal JDK APIs to shadow Android framework classes. Starting with JDK 17, these internal APIs are protected, necessitating explicit `--add-opens` flags.

| JVM Argument | Reason |
| :--- | :--- |
| `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` | Internal JDK access for framework shadowing |
| `--add-opens=java.base/java.io=ALL-UNNAMED` | Required for file-based resource loading in Robolectric |
| `--add-opens=java.base/java.net=ALL-UNNAMED` | Required for shadowing networking components |
| `--add-opens=java.base/java.util=ALL-UNNAMED` | Access to core collection internals for shadowing |
| `--add-opens=java.base/java.lang=ALL-UNNAMED` | Reflection access for core Java classes |
| `--add-opens=java.base/java.lang.reflect=ALL-UNNAMED` | Deep reflection access required by Robolectric/Mockito |
| `--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED` | JAR protocol access for resource resolution |
| `--add-opens=java.desktop/java.awt.font=ALL-UNNAMED` | Required for UI-related shadowing in Robolectric |

### Other Arguments

- `-Dnet.bytebuddy.experimental=true`: Allows Mockito to self-attach and function correctly on JDK 17+.

## Best Practices

- **Robolectric Consistency**: Always run Robolectric tests with the configured `jvmArgs` to prevent `InaccessibleObjectException`.
- **Fail on Empty**: By default, subprojects are configured with `failOnNoDiscoveredTests = true` to ensure tests are not accidentally skipped. The `:app` module is an exception (only has instrumentation tests).
