# Scheduling, Resume & Integrity Mechanisms

This page explains the concrete scheduling formulas, retry budget boundaries, `.meta` v2 persistence protocol, commit sequence, and `aria2` dead-man's switch.

## Mirror Selection & Failover Guardrails

1. **Source Scoring**:
   - `Policy.fastestFirst()` computes `(ema > 0 ? ema : s.weight * 1024L * 1024L) / (1.0 + inFlight)`, updating EMA after each chunk via `0.3 * bps + 0.7 * prevEma`.
   - `Policy.weighted()` performs atomic weighted round-robin using `Source.weight`.
2. **Failure Decisions & Retry Budget**:
   - Default failure logic: `consecutiveFailures >= 3` -> `DISABLE_SOURCE`; `attempt <= 1` -> `RETRY_SAME`; else -> `SWITCH_SOURCE`. Runtime disable flags reset at the start of every `download()` call.
   - `Downloader` bounds each chunk to `MAX_ATTEMPTS_PER_CHUNK = 6`, exponential backoff `min(2000, 200 << min(attempt - 1, 16))` ms (polling `Cancellation` every `50` ms), and `chunkRetryBudgetMs` (default `60_000` ms) counting **only failure time and backoff** so healthy slow mirrors are never prematurely aborted.

## Resumable State (`.meta` v2) & Commit Order

When `allowResume == true` and `probe.rangeSupported == true`, `ResumeState` writes `<target>.part` alongside `<target>.meta` using header `anabranch-meta/2` (`total`, `etag`, `source`, `unit`, `count`, `state` of `'P'`/`'D'`/`'V'`). State updates persist via `.meta.tmp` + `fsync` -> `renameTo` -> directory sync marker `.anabranch-dsync`.

| Check | Same-Source Resume | Cross-Source Failover Resume |
| --- | --- | --- |
| `total` & `unit` | Must match | Must match |
| `ETag` | Compared when `probeSourceId == meta.source`; mismatch discards `.part`/`.meta` | Skipped (mirrors emit independent ETags); relies on `Integrity` digests |

Commit sequence inside `Downloader.commit(...)`:
1. `ch.force(true)` flushes `.part` pages to storage.
2. Verify `ResumeState.isComplete()`.
3. If `wholeSha256` is configured, hash the full `.part` file and abort before renaming on mismatch.
4. Execute `target.delete()`, `part.renameTo(target)`, `ResumeState.syncDir(...)`, `st.discard()`, and `listener.onProgress(total, total)`. Note that `delete()` + `renameTo()` is not atomic across arbitrary filesystems if renaming fails.

## Out-of-Process `aria2` Dead-Man's Switch

Because native `SIGSEGV`/`SIGABRT` crashes terminate the JVM without giving Java `catch` blocks a chance to run, `FileBackendSelector` persists two files in `stateDir`:
- `arm(backend)` writes and `fsync`s `aria2.armed` **before** calling native code.
- `disarm()` removes `aria2.armed` **only after** clean `sessionShutdown` completes within `DRAIN_BUDGET_MS = 10_000`.
- On next startup, if `aria2.armed` still exists, `current()` persists the opposite backend (`aria2-openssl` <-> `aria2-gnutls`) to `aria2.backend` first and then clears `aria2.armed`, ensuring idempotency even across repeated crashes.
