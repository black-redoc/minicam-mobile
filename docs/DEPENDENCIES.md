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
| `com.android.application` (AGP, compiles Kotlin itself since AGP 9) | 9.4.1 |
| `org.jetbrains.kotlin.plugin.compose` | 2.4.21 |
| `org.jetbrains.kotlin.plugin.serialization` | 2.4.21 |

## Android runtime
| Dependency | Version | Why |
|---|---|---|
| androidx.compose BOM | 2026.09.00 | Compose UI ([ADR 0001](adr/0001-native-kotlin-compose-and-swiftui.md)) |
| androidx.activity:activity-compose | 1.13.0 | Compose host activity |
| androidx.lifecycle (viewmodel-compose, runtime-compose) | 2.11.0 | ViewModel + lifecycle-aware streaming |
| androidx.browser:browser | 1.10.0 | Custom Tabs for the Access login ([ADR 0002](adr/0002-cloudflare-access-handoff.md)) |
| com.squareup.okhttp3:okhttp | 5.5.0 | HTTP + WebSocket (no platform WebSocket client) |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.12.0 | JSON parsing |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.11.0 | async |
| androidx.media3 (exoplayer, ui, datasource-okhttp) | 1.11.1 | Recording playback with Range/seek over the app's OkHttp client ([ADR 0005](adr/0005-event-recordings-in-the-apps.md)); brings Guava transitively (R8 strips unused code) |

## Android test
| Dependency | Version | Why |
|---|---|---|
| junit:junit | 4.13.2 | unit tests |
| com.squareup.okhttp3:mockwebserver3 | 5.5.0 | fake minicam server in tests |
| org.jetbrains.kotlinx:kotlinx-coroutines-test | 1.11.0 | coroutine tests |

## iOS
No third-party packages: URLSession (HTTP + WebSocket), Security (Keychain),
AuthenticationServices, SwiftUI, AVKit (recording playback), Swift Testing.

## CI (GitHub Actions, pinned by commit SHA)
| Action | Version |
|---|---|
| actions/checkout | v7.0.1 |
| actions/setup-java | v6.1.0 |
| actions/upload-artifact | v7.0.2 |
| actions/download-artifact | v8.0.2 |
| gradle/actions/setup-gradle | v6.4.0 (Gradle cache + wrapper checksum validation) |

## Dev tools (not shipped)
| Tool | Version | Why |
|---|---|---|
| Gradle | 9.8.2 | Android build (wrapper, `distributionSha256Sum` pinned) |
| XcodeGen | 2.46.0 | Generates the Xcode project from `ios/MinicamApp/project.yml` so no `.pbxproj` is hand-edited (CI checks the zip SHA-256) |

Last bumped 2026-10-21 to the latest stable releases on Maven Central / Google Maven.
Lockfiles: `android/app/gradle.lockfile`, `android/settings-gradle.lockfile`.
