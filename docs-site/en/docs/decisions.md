# Design Decisions & Releasing

This page preserves key architectural rationale behind Anabranch Core's clean-room design, the empirical deprecation of `Aria2Engine`, `.meta` compatibility guarantees, and the release process.

## Core Design Rationale

1. **Stateless Library Boundary**: The library stores no hard-coded mirror tables, ships zero URLs, writes no preferences, and binds no logging framework (`NONE` defaults for `Logger`, `Listener`, `Cancellation`). `Source.Resolver` replaces naive `base + name` concatenation so signed cloud URLs and mismatched mirror paths work cleanly.
2. **Five Explicit Non-Goals**: Anabranch Core does not handle APK/archive extraction or install, foreground notifications/UI, cross-restart task orchestration, bundled remote configs, or domain-specific manifest concepts.
3. **Separate `Listener` and `Cancellation` + Raw `bytes/sec`**: Splitting observation from polling cancellation avoids forcing progress listeners to implement cancel logic, and `Listener.onSpeed(double)` reports raw bytes/sec without ambiguous MB/MiB scaling.
4. **Why Keep `Downloader.probeAll`**: Although `download()` probes only until the first usable source for speed, `probeAll()` gives callers a low-cost preflight to detect stale mirrors whose file sizes diverge before downloading chunks.

## Why `Aria2Engine implements Engine` Was Retired

On **2026-09-01**, testing `aria2 1.37.0` against a request-logging loopback HTTP server showed that feeding a custom `Range: bytes=1000-1999` header into aria2 causes a fatal protocol conflict: aria2 writes the 1000 bytes, then reads the total file length from the `206 Content-Range` header and hangs indefinitely waiting for the remaining bytes instead of exiting or failing.

Per the rule against half-working fallback paths, `Aria2Engine` was removed entirely in favor of `Aria2Downloader` (whole-file multi-URI `addUri`). At the same time, control moved away from `argv` + localhost JSON-RPC to the `--enable-libaria2` public C++ session API driven by a single Java `runOnce` loop—eliminating local RPC ports, secrets, cross-thread calls, and `exit()` aborts on bad CLI flags. See [`docs/ARIA2.md`](https://github.com/CyberNova2333/anabranch-core/blob/main/docs/ARIA2.md) for full details.

## Versioning, `.meta` Contract & Releasing

- **`0.x.y` API Stability**: Java public signatures may evolve during `0.x.y`.
- **`.meta` Resume Format Guarantee**: Unknown `.meta` versions (current header `anabranch-meta/2`) are safely discarded rather than misread; any incompatible `.meta` change bumps at least the minor version.
- **Manual Publishing (`docs/RELEASING.md`)**: Releases are strictly manual—either locally (`gradle build` + `gradle centralBundle`) or via `.github/workflows/build-core.yml` (`workflow_dispatch`, optionally compiling and bundling `anabranch-aria2` AAR and corresponding GPL source tarball). Consult [`docs/RELEASING.md`](https://github.com/CyberNova2333/anabranch-core/blob/main/docs/RELEASING.md) and [`aria2/THIRD-PARTY-NOTICES.md`](https://github.com/CyberNova2333/anabranch-core/blob/main/aria2/THIRD-PARTY-NOTICES.md) before publishing.
