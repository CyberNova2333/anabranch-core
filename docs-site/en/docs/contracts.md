# Public API & Extension Contracts

This page summarizes public contracts in `io.anabranch`, hard rules for custom extensions, and key semantic differences when opting into `io.anabranch.aria2.Aria2Downloader`.

## Public Core Contracts (`io.anabranch`)

| Type | Key Members | Contract Guarantee |
| --- | --- | --- |
| `Downloader` | `download(Request, SourceSet, Listener, Cancellation)`<br>`download(Request, String url, Listener, Cancellation)`<br>`probeAll(String key, SourceSet, Cancellation)` | Thread-safe reusable coordinator; resets per-source runtime disable flags on every `download()` |
| `Request` | `key`, `target`, `integrity`, `chunks`, `allowResume`, `Request.of(...)` | `chunks <= 0` falls back to builder default; `Request.of` omits digests and sets `allowResume=true` |
| `Result` | `totalBytes`, `etag`, `chunkVerified`, `transferredBytes` | `etag` defaults to `""` (never null); `transferredBytes` distinguishes resumed vs full downloads |
| `Source` | `id`, `weight` (`>=1`), `maxChunks`, `enabled`, `Resolver` | Callers guarantee mirrors serve identical bytes; note `maxChunks` is currently metadata only |
| `SourceSet` | `SourceSet(List<Source>, Policy)` / `SourceSet.single(url)` | Defaults to `Policy.fastestFirst()`; holds EMA and failure `Stats` |
| `Integrity` | `chunkSize`, `chunkHashes[]`, `wholeSha256`, `wholeOnly(...)` | Lowercase hex SHA-256 strings; when `chunkHashes` exist, downloader unit size aligns to `chunkSize` |
| `ProbeReport` | `ok`, `failed`, `sizesAgree`, `size`, `etagsAgree`, `anyUsable()` | `etagsAgree == false` is advisory and does not block cross-source resume |
| `Listener` | `onTotal`, `onProgress`, `onSpeed(double bytesPerSec)`, `onChunkDone`, `onChunkVerifyFailed`, `onSourceSwitch` | Default methods are no-op (`Listener.NONE`); speed is strictly **bytes/sec** |
| `Cancellation` | `boolean isCancelled()` / `Cancellation.NONE` | Polled across reads, rate limiting, and 50ms backoff loops |

## Extension Contracts: `Engine`, `Policy`, `Source.Resolver`

- **Custom `Engine`** (e.g., OkHttp): Implement `probe(url, cancel)` (`HEAD` with `GET Range: bytes=0-0` fallback) and `fetchRange(url, start, endInclusive, out, listener, cancel)`. Must reject HTTP `200` when `start > 0`, throw if written byte count differs from `endInclusive - start + 1`, poll `Cancellation`, and obey `tools/check-core-boundaries.py` (no `android.*`, zero third-party deps inside `src/io/anabranch/`, no dependency on `Downloader`/`SourceSet`/`Policy`/`Stats`/`ResumeState`).
- **Custom `Source.Resolver`**: Provide dynamic URL resolution for mirrors with differing paths, pre-signed URLs, or expiring CDN tokens via `urlFor(key)`.
- **Custom `Policy`**: Read `Stats` (`emaBytesPerSec`, `consecutiveFailures`, `inFlight`, `lastSuccessAtMillis`, `isDisabled`) and return `Source` from `pick(...)` plus `Decision` (`RETRY_SAME`, `SWITCH_SOURCE`, `DISABLE_SOURCE`, `ABORT`) from `onFailure(...)`.

## `Aria2Downloader` Behavioral Differences

`Aria2Downloader` shares `Request`, `SourceSet`, `Listener`, `Cancellation`, and `Result`, allowing drop-in backend switching at the call site, but enforces three different internals:

1. **Internal routing by `libaria2`**: All available URLs are resolved via `urlFor(key)` and passed together to `addUri`. Core `Policy`, `Stats`, and `Source.weight` do not participate; only `Request.chunks > 0` maps to aria2 `split`.
2. **Process-wide serialization**: Calls synchronize on `BUSY` and drive `libaria2` via single-threaded `runOnce` polling (with 200ms callback throttling and a 10s shutdown drain budget).
3. **Post-download verification**: Integrity checks run after `.a2part` completes. Verification failures delete both `.a2part` and `.a2part.aria2`, whereas network errors keep them for resumption.

## Extension FAQ

- **Why is `Cancellation` polled instead of using `Thread.interrupt()`?** Interrupts linger on caller worker threads and break unrelated downstream I/O after cancellation.
- **Why isn't `Source.maxChunks` enforced yet?** The field exists for API completeness and future per-source caps; today concurrency is bounded globally by `Builder.maxConcurrent` and balanced via `1.0 + inFlight` in `Policy.fastestFirst()`.
