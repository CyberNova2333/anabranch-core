# Code Map & Directory Index

The repository splits cleanly into the pure-Java core (`src/io/anabranch/`, 19 files), loopback HTTP test suites (`test/`), the optional native `aria2` wrapper (`aria2/`), and verification/commit guardrails (`tools/`).

## Directory Overview

| Path | Responsibility | Key Entry Points | Role in System |
| --- | --- | --- | --- |
| `src/io/anabranch/` | Pure Java 8 multi-source core | `Downloader`, `SourceSet`, `HttpEngine` | Handles probing, scheduling, resume, verification, and commit |
| `test/` | Loopback real-HTTP regression suites | `SingleSourceTest`, `MultiSourceTest`, `ThrottleTest` | Tests chunk corruption, ETag invalidation, failover, budget, throttling |
| `aria2/` | Optional native libaria2 backend | `Aria2Downloader`, `FileBackendSelector`, `aria2/native/` | Standalone GPL-3.0-or-later AAR wrapper and host test harness |
| `tools/` | Java 8 compilation & static boundaries | `run-checks.sh`, `check-core-boundaries.py` | Enforces `--release 8`, zero deps, and unidirectional layering |

## Key Classes in `src/io/anabranch/`

| Files | Function | Key Symbols |
| --- | --- | --- |
| `Downloader.java` | Orchestration, thread pool, retry/backoff budget, commit | `newBuilder`, `download`, `probeAll`, `commit` |
| `Request.java` / `Result.java` | Immutable request config and completion stats | `Request.of`, `allowResume`, `chunkVerified`, `transferredBytes` |
| `Source.java` / `SourceSet.java` | URL resolver callback, runtime disable state, EMA stats | `Source.Resolver`, `recordSuccess`, `available`, `resetDisabled` |
| `Policy.java` / `Stats.java` / `ChunkRef.java` | Source picking & failure state machine | `fastestFirst`, `weighted`, `Decision` |
| `ResumeState.java` / `Integrity.java` | `.part` / `anabranch-meta/2` persistence & SHA-256 digests | `matches`, `hashFor`, `PENDING`, `DOWNLOADED`, `VERIFIED` |
| `Engine.java` / `HttpEngine.java` / `Probe.java` / `ProbeReport.java` | Range transport contract and `HttpURLConnection` implementation | `probe`, `fetchRange`, `Accept-Encoding: identity` |
| `RateLimiter.java` / `SpeedMeter.java` / `Listener.java` / `Cancellation.java` / `Logger.java` | Token-bucket limiter, 3s sliding window meter, callbacks, polling cancel | `acquire`, `onSourceSwitch`, `isCancelled` |

## Tests & Native Entries

- **`test/SingleSourceTest.java`** (21 assertions): probe metadata, mid-transfer resume, chunk #2 corruption interception, same-source ETag invalidation, non-Range fallback.
- **`test/MultiSourceTest.java`** (33 assertions): mirror distribution, fail-over switching, all-dead reporting, retry budget vs unlimited attempts, backoff timing, `fastestFirst` overload fallback.
- **`test/ThrottleTest.java`** (13 assertions): write-side token bucket rate convergence and cancellation responsiveness.
- **`aria2/test/Aria2FailoverTest.java`** (10 assertions) & **`aria2/test/Aria2DownloaderTest.java`** (28 host assertions): `FileBackendSelector` disk transitions and full `libaria2` 1.37.0 loopback downloads.

See [`repo-docs/README.md`](https://github.com/CyberNova2333/anabranch-core/blob/main/repo-docs/README.md) in the repository for the Chinese Standard source guide and evidence logs.
