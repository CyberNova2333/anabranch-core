# Architecture & One Real Run

Anabranch Core maintains strict unidirectional layering: coordination and state persistence invoke the pure range-transport contract, while transport engines never reference scheduling state. The optional `aria2` native backend lives in a separate module reusing `Request`, `SourceSet`, `Listener`, and `Cancellation`.

```text
  Caller (JVM / Android minSdk 21)
     │
     ├─► Pure Java Core (io.anabranch.*) ───────────────────────────────────────┐
     │     Downloader ─┬─► SourceSet + Policy + Stats (EMA pick / failover)     │
     │                 ├─► ResumeState (.part / .meta v2) + Integrity (SHA-256) │
     │                 └─► Engine (HttpEngine) ──► Range fetch + RateLimiter    │
     │                                                                          │
     └─► Optional Native Backend (io.anabranch.aria2.*, separate GPL AAR)       │
           Aria2Downloader ─► FileBackendSelector (dead-man's switch)           │
                           └─► Aria2Native (runOnce) ──► libaria2 .so ──────────┘
```

## Preserved Interactive Diagram Artifact

Below is the preserved interactive static page [`/diagrams/anabranch-architecture.html`](/diagrams/anabranch-architecture.html). **Note**: that legacy diagram depicts repository multi-remote sync helpers rather than the Java multi-source chunked downloader itself. Refer to the architecture diagram above and the step-by-step trace below for the actual download library implementation.

<div style="margin: 16px 0; border: 1px solid var(--vp-c-divider); border-radius: 12px; overflow: hidden;">
  <iframe src="/diagrams/anabranch-architecture.html" style="width: 100%; height: 520px; border: 0;" title="Preserved Legacy Static Diagram"></iframe>
</div>

## One Real Run Walkthrough

1. **Submit Request & Sources (`Request`, `SourceSet`, `Integrity`)**: The caller provides `key`, `target`, optional chunk/whole-file SHA-256 digests (`Integrity`), and candidate mirrors via `SourceSet`. Shorthand `Request.of(...)` leaves `integrity == null` and cannot detect mirror drift across origins.
2. **Probe First Mirror & Open `.meta` v2 (`Downloader.probeFirst`, `ResumeState`)**: Runtime disabled flags reset via `SourceSet.resetDisabled()`. `Engine.probe` retrieves `total`, `etag`, and `rangeSupported`. If `allowResume && rangeSupported`, `ResumeState` reuses `<target>.part` + `<target>.meta` when `total`, `unit`, and same-source `etag` match (cross-source resume skips `ETag` comparison).
3. **Chunk Dispatch, Verification & Failover (`fetchOneChunk`)**: Fixed byte intervals `[start, endInclusive]` are dispatched up to `maxConcurrent`. Each completed chunk moves `'P'` -> `'D'`, computes per-chunk SHA-256 if provided (reverting to `'P'` and throwing on mismatch), and only then marks `'V'` and records source EMA throughput (`0.3 * bps + 0.7 * prevEma`). Write throughput passes through `RateLimiter`.
4. **Flush, Whole-File Check & Commit (`Downloader.commit`)**: Once all chunks finish, `.part` is flushed via `force(true)`, verified against optional `wholeSha256`, and swapped via `target.delete()` -> `part.renameTo(target)` -> `syncDir` -> `.meta` cleanup. Non-resumable failures delete `.meta` and `.part` in the catch handler.

For the full Chinese source-grounded walkthrough and module deep dives, see `repo-docs/walkthroughs/one-real-run.md` or browse the [Code Map](/en/docs/code-map).
