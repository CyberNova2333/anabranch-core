# Integration, aria2 Build & FAQ

This page documents builder configuration defaults, Android `minSdk 21` constraints, optional `aria2` JNI build workflows, and common runtime troubleshooting.

## Configuration Matrix

### `Downloader.Builder`

| Method | Default | Behavior & Boundaries |
| --- | --- | --- |
| `engine(Engine e)` | `new HttpEngine()` | Swaps transport layer; must be non-null |
| `defaultChunks(int n)` | `8` | Minimum `1`; used when `Request.chunks <= 0` and no `Integrity` chunks exist |
| `maxConcurrent(int n)` | `4` | Minimum `1`; thread-pool concurrency cap across chunks |
| `chunkRetryBudgetMs(long ms)` | `60_000` | Per-chunk budget counting **failed attempts and backoff only** (`<= 0` unlimited) |
| `bytesPerSecond(long n)` | `0` (unlimited) | Whole-download write-side token bucket limit (`<= 0` disabled) |
| `logger(Logger l)` | `Logger.NONE` | Diagnostic logger callback; library embeds no logging dependency |

### `Request` & `HttpEngine`

- `Request(String key, File target, Integrity integrity, int chunks, boolean allowResume)` or `Request.of(key, target)` (`integrity=null`, `chunks=0`, `allowResume=true`).
- `HttpEngine(int connectTimeoutMs, int readTimeoutMs, String userAgent, Logger log)` defaults to `15_000` ms connect, `30_000` ms read, UA `"anabranch/0.1"`, and always sends `Accept-Encoding: identity`.

### `Aria2Downloader.Builder` (Optional AAR)

| Method | Default | Behavior |
| --- | --- | --- |
| `stateDir(File dir)` | **Required** | Persists `aria2.backend` and `aria2.armed` failover markers |
| `selector(BackendSelector s)` | `FileBackendSelector(stateDir, "aria2-openssl", "aria2-gnutls")` | Handles load-time and crash-time TLS backend switching |
| `logger(Logger l)` | `Logger.NONE` | Logs warnings such as ignored `Source.weight` |

## Android `minSdk 21` Integration Rules

1. **No desugaring needed**: Core and `aria2` Java layers compile strictly under `javac --release 8` and avoid API 24+/26+ runtime classes like `java.nio.file` or `java.util.function`.
2. **Permissions**: Request `android.permission.INTERNET`, and allow cleartext traffic in `networkSecurityConfig` if testing against plain HTTP endpoints.
3. **Target directory**: Placing `Request.target` in app-private storage ensures `.part` and `.meta` stay writable without extra storage permissions.

## Optional `aria2` Native Build & Host Tests

Native JNI glue under `aria2/native/` pins `aria2 1.37.0` and 8 SHA-256-verified dependencies on NDK r25c:

```sh
# Build host .so and run 28 real loopback HTTP download tests
sh aria2/native/run-host-tests.sh

# Slim build (excludes libxml2 / SQLite / libssh2)
ARIA2_SLIM=1 sh aria2/native/run-host-tests.sh
```

Android multi-ABI `.so` compilation and AAR assembly run manually via [`.github/workflows/build-aria2.yml`](https://github.com/CyberNova2333/anabranch-core/blob/main/.github/workflows/build-aria2.yml), including 16K page alignment checks, armv7 API-21 `UND` symbol diff guards, and [`aria2/THIRD-PARTY-NOTICES.md`](https://github.com/CyberNova2333/anabranch-core/blob/main/aria2/THIRD-PARTY-NOTICES.md) bundling. Never `System.load` both TLS backends in the same process—both export identical `ANABRANCH_ARIA2_1.0` symbols, so persisted swaps activate on the next process start.

## Troubleshooting FAQ

### 1. Why is there no `./gradlew` in the repository?
The wrapper JAR is intentionally excluded. Daily development relies on `sh tools/run-checks.sh` (JDK + Python 3); local Gradle 8+ is only required when packaging a Maven Central bundle via `gradle centralBundle`.

### 2. Why did `HttpEngine` abort with an error about HTTP `200` on a Range request?
When requesting an interior offset (`start > 0`) and a server ignores `Range` by streaming HTTP `200` from byte `0`, `HttpEngine` throws immediately to prevent silent corruption. Full-stream fallback happens only when initial probing reports `rangeSupported == false`.

### 3. Are `.part` and `.meta` leftovers after a failed download a bug?
When `allowResume == true` and the server supports `Range`, preserving `.part` and `.meta` enables resumption on the next attempt. When non-resumable (`!allowResume` or `!rangeSupported`), `Downloader` cleans up both files on failure.

### 4. How does `Aria2Downloader` handle `UnsatisfiedLinkError` vs native crashes?
Load failures trigger `selector.reportLoadFailure(backend)` to try the alternate TLS backend immediately in the same download call. Native runtime crashes (`SIGSEGV`/`SIGABRT`) leave the `fsync`'d `aria2.armed` file intact so `selector.current()` switches backends on the next process launch.
