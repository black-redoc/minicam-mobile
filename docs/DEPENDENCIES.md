# Dependencies

## Policy (agreed 2026-10-21)
- **Exact versions only**, no ranges, no `+`, no `latest`.
  - Android: every version lives in `android/gradle/libs.versions.toml`; Gradle dependency locking
    is on (`gradle.lockfile` committed after the first `./gradlew dependencies --write-locks`).
  - iOS: SwiftPM `exact:` requirements; `Package.resolved` committed.
- **Lockfiles are always committed.**
- **No build-time code from third parties beyond the official toolchain**: Gradle plugins are
  limited to the allowlist below; SwiftPM build/command plugins are not allowed unless added
  here with an ADR.
- Prefer platform APIs. A new runtime dependency needs a justification here; a new framework
  needs the user's approval and an ADR.

## Gradle plugin allowlist
| Plugin | Version |
|---|---|
| `com.android.application` (AGP) | 8.7.3 |
| `org.jetbrains.kotlin.android` | 2.1.0 |
| `org.jetbrains.kotlin.plugin.compose` | 2.1.0 |
| `org.jetbrains.kotlin.plugin.serialization` | 2.1.0 |

## Android runtime
| Dependency | Version | Why |
|---|---|---|
| androidx.compose BOM | 2024.12.01 | Compose UI ([ADR 0001](adr/0001-native-kotlin-compose-and-swiftui.md)) |
| androidx.activity:activity-compose | 1.9.3 | Compose host activity |
| androidx.lifecycle (viewmodel-compose, runtime-compose) | 2.8.7 | ViewModel + lifecycle-aware streaming |
| androidx.browser:browser | 1.8.0 | Custom Tabs for the Access login ([ADR 0002](adr/0002-cloudflare-access-handoff.md)) |
| com.squareup.okhttp3:okhttp | 4.12.0 | HTTP + WebSocket (no platform WebSocket client) |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.7.3 | JSON parsing |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.9.0 | async |

## Android test
| Dependency | Version | Why |
|---|---|---|
| junit:junit | 4.13.2 | unit tests |
| com.squareup.okhttp3:mockwebserver | 4.12.0 | fake minicam server in tests |
| org.jetbrains.kotlinx:kotlinx-coroutines-test | 1.9.0 | coroutine tests |

## iOS
No third-party packages: URLSession (HTTP + WebSocket), Security (Keychain),
AuthenticationServices, SwiftUI, Swift Testing.

## Dev tools (not shipped)
| Tool | Version | Why |
|---|---|---|
| Gradle | 8.11.1 | Android build (wrapper) |
| XcodeGen | 2.42.0 | Generates the Xcode project from `ios/MinicamApp/project.yml` so no `.pbxproj` is hand-edited |

Versions were pinned at bootstrap; bumping them is a Backlog item (ROADMAP.md).
