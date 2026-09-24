# Cancellable streaming on Android — client decision

Status: **decision pending (Ku)**. Research only; no dependency added, no production code changed.

## The defect this has to solve

A stopped turn does not settle while the runtime is parked in a body read. Measured on
`bram_api35`: after Stop the UI sat at `Stopping…` / `Writing…` past 26s.

`OpenAiCompatibleRuntime` blocks in `reader.readLine()` on `Dispatchers.IO`, and cancelling a
coroutine cannot interrupt a thread blocked on a socket. Measured against a silent peer:
`HttpURLConnection.disconnect()` left the read parked and it returned only on **its own 20s read
timeout (19.5s later)**, with `SocketTimeoutException: Read timed out`. `InputStream.close()` was no
better. `disconnect()` closes the *idle connection cache*, not the response being read, so **no close
strategy reaches a parked read**.

The regression test is in `OpenAiCompatibleRuntimeTest` (`@Ignore`d with this reason).

## Verified facts — this repository

| Fact | Value | Source |
|---|---|---|
| minSdk / targetSdk / compileSdk | 29 / 36 / 36 | `app/build.gradle.kts:12-13`, `runtime/openai/build.gradle.kts:7-10` |
| Java | 17 source/target | `app/build.gradle.kts:24-26` |
| AGP / Kotlin / coroutines | 9.3.1 / 2.3.21 / 1.10.2 | `gradle/libs.versions.toml:2-9` |
| Core library desugaring | **not configured anywhere** | grep: no match in any Bram Gradle file |
| HTTP client dependencies today | **none** | `runtime/openai/build.gradle.kts:19-25` (core:domain, coroutines-android, junit, org.json) |
| TLS customization | **none** — no `TrustManager`, `SSLContext`, `HttpsURLConnection`, `HostnameVerifier` | grep over `runtime/openai/src/main` |
| Proxy configuration | **none** — plain `URL.openConnection()`, so platform default `ProxySelector` | `OpenAiCompatibleRuntime.kt:335`, `RemoteModelCatalog.kt:34,97` |
| Cleartext | allowed app-wide by policy | `AndroidManifest.xml:37` `usesCleartextTraffic="true"` |
| Auth | `Authorization: Bearer` header only, from `EndpointCredentialResolver` | `OpenAiCompatibleRuntime.kt:345` |
| Timeouts | connect 15s, read **120s**; catalog 10s/15s | `OpenAiCompatibleRuntime.kt:337-338`, `RemoteModelCatalog.kt:36-37` |
| Existing cancel hook | `cancel(requestId)` already disconnects via `activeConnections` — **nothing calls it on coroutine cancellation** | `OpenAiCompatibleRuntime.kt:85-87` |
| Networking call sites | 2: `OpenAiCompatibleRuntime` (733 lines), `RemoteModelCatalog` (133) | — |
| Test harness | JVM unit tests against `com.sun.net.httpserver`; 21 runtime + catalog tests | `OpenAiCompatibleRuntimeTest.kt` |

Nothing custom to port: no pinning, no custom trust, no proxy config, no interceptors.

## Verified facts — external

**`java.net.http.HttpClient` is not an option.** It is absent from Android's SDK and absent from
every AGP desugaring support table (the Java 8/11 tables list `java.util.function`, `java.time`,
`java.util.stream`, `java.nio` — no `java.net.http`). Unavailable at minSdk 29, and not made
available by desugaring.

**OkHttp** (latest **5.5.0**; 4.x final **4.12.0**):

- 4.x requires Java 8+ / Android 5+ (official upgrade guide) — satisfied.
- 5.x splits JVM and Android artifacts. The `okhttp-android:5.5.0` POM pulls `kotlin-stdlib 2.1.21`,
  `okio-jvm 3.18.1`, `androidx.annotation 1.10.0`, and **`androidx.startup:startup-runtime 1.2.0`**.
- Measured artifact weight (raw, unshrunk): `okhttp-4.12.0.jar` **771 KB**; `okhttp-android-5.5.0.aar`
  `classes.jar` **927 KB** + `PublicSuffixDatabase.list` 130 KB; `okhttp-jvm-5.5.0.jar` 939 KB;
  `okio-jvm-3.18.1.jar` **383 KB**. So roughly **1.15 MB** for 4.x vs **1.44 MB + AndroidX Startup**
  for 5.x, before R8 shrinking.
- **It provides exactly the guarantee `HttpURLConnection` lacks.** Official docs: *"Calls can be
  canceled from any thread… Code that is writing the request body or reading the response body will
  suffer an `IOException` when its call is canceled"*, and `RealCall`: *"Immediately closes the socket
  connection if it's currently held. Use this to interrupt an in-flight request from any thread."*

**Ktor** (latest **3.6.0**): CIO is available on Android and is coroutine-based (cancellable by
design), but the official engine table marks CIO **HTTP/1.x only** (no HTTP/2), while the OkHttp
engine gives HTTP/2 and WebSockets. Using the OkHttp engine means carrying Ktor *and* OkHttp.

**Cronet** on Android is delivered by **Google Play services**
(`com.google.android.gms:play-services-cronet` + `org.chromium.net:cronet-api`, requiring
`CronetProviderInstaller.installProvider`). Where Play services is absent you must package a
fallback: `cronet-embedded` costs **~8 MB** (androidx docs) and `cronet-fallback` is less
performant. This conflicts with Bram being local-first, privacy-respecting and sideloadable — the
remote path would become unavailable on de-Googled and Play-Store-less devices.

## Assumptions (not measured here)

- R8 shrinks the real APK delta below the raw artifact sizes above; only debug builds were measured.
- No endpoint Bram targets *requires* HTTP/2 (providers vary; SSE works over HTTP/1.1).
- OkHttp's default `ProxySelector` behaviour matches the platform default the code relies on today.
- No future requirement for certificate pinning or OkHttp interceptors.

## Decision table

| Option | Cancels a parked read | Works at minSdk 29 | Added deps (raw) | Parity risk | Migration surface | Verdict |
|---|---|---|---|---|---|---|
| **OkHttp 5.5.0** | **Yes** — documented socket close on cancel | Yes | ~1.44 MB + `androidx.startup` | Low — no custom TLS/proxy to port; cleartext already allowed app-wide | One module, 2 call sites | **Recommended** |
| OkHttp 4.12.0 | Yes | Yes | ~1.15 MB, no AndroidX Startup | Same | Same | Acceptable fallback; superseded line, no ongoing fixes |
| Ktor + CIO | Yes (coroutine-native) | Yes | Ktor core + CIO (CIO HTTP/1.x only) | Medium — loses HTTP/2 and any OkHttp tooling; new framework layer | Larger: whole client + pipeline | Not recommended |
| Ktor + OkHttp engine | Yes | Yes | Ktor **and** OkHttp | Low | Largest — two abstractions | Not recommended |
| `java.net.http` | Yes | **No** — absent from Android and from desugaring | none | — | — | **Ruled out** |
| Cronet (Play services) | Yes | Android 10+ but **requires Play services** | `play-services-cronet` + `cronet-api`; ~8 MB embedded fallback | **High** — remote path dies without Play services, against the product's posture | Large | **Rejected** |
| Keep `HttpURLConnection` | **No** | Yes | none | — | none | Defensible only with the UI-settle mitigation, since the defect is real though bounded by the 5-minute stall watchdog |

**Recommendation: OkHttp 5.5.0, introduced in `runtime:openai` only.** It is the smallest change
that is correct long-term: the one guarantee needed is documented, the dependency weight is modest
next to the app's existing native payload, there is no custom TLS/proxy/interceptor behaviour to
port, and AndroidX is already a large part of the app, so `androidx.startup` is not a new burden.
4.12.0 is the fallback if avoiding `androidx.startup` matters more than being on the supported line.

## Phased migration and test plan

**Phase 0 — acceptance gate (no production change).** Un-`@Ignore`
`cancelling a silent stream ends the turn instead of waiting out the read timeout`. It must go from
failing (116s) to passing (<5s). Everything after is judged by this test.

**Phase 1 — generation path only.** Add OkHttp to `runtime:openai`; replace
`openConnection`/`readSseLines` for `streamChat` and `streamResponses`, keeping the existing
`GenerationEvent` surface, the retry taxonomy (`openWithRetry`'s 408/429/5xx handling and
`EndpointConfigurationException` for 4xx), provider quirks, and the SSE line parser. Wire
`Call.cancel()` to coroutine cancellation, which also lets `activeConnections`/`cancel(requestId)`
stay as the explicit-stop path. Gate: all 21 existing contract tests green unchanged, plus Phase 0
passing. `RemoteModelCatalog` deliberately stays on `HttpURLConnection` so the diff stays reviewable.

**Phase 2 — remaining reads.** Migrate `readWholeBody` and `RemoteModelCatalog`, then delete the
`HttpURLConnection` usage from the module. Gate: full `:runtime:openai:test`, `:app:testDebugUnitTest`.

**Phase 3 — device verification.** Emulator mock suite (44 tests) unchanged, plus a new scenario:
stop a turn against the silent peer and assert it settles promptly. Then the phone against a real
endpoint, confirming headers/auth/HTTP behaviour and that a normal turn is unaffected. Measure the
real APK delta with a release build.

**Rollback.** Keep the old reader selectable for one slice behind an internal flag, so a bad
provider interaction is a one-line revert rather than a revert of the dependency.
