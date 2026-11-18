# Overview & Quick Start

Anabranch Core solves multi-source segmented downloading and verified delivery for a single file. Instead of pulling segments from one fixed URL, it probes available mirrors, dispatches byte ranges across mirrors using throughput-aware policies, fails over per chunk, and verifies SHA-256 digests before moving the final file into place.

## Capability Comparison

| Capability | Android `DownloadManager` | Typical Single-Source Downloaders | `Anabranch Core` |
| --- | --- | --- | --- |
| Multi-mirror parallel chunking | No | No | Yes (`SourceSet` + `Policy`) |
| Per-chunk failover & circuit breaker | No | No | Yes (`RETRY_SAME` / `SWITCH_SOURCE` / disable) |
| Same-source ETag & cross-source resume | Opaque internal state | Usually single-URL bound | `anabranch-meta/2` + `Integrity` digests |
| Zero deps / Android `minSdk 21` | System service | Usually pulls extra libs | 19 plain Java 8 files, no desugaring |
| Optional native accelerator | No | No | Standalone `anabranch-aria2` AAR (`libaria2`) |

## Two-Artifact Isolation

To protect pure-Java integrators from native binary bloat and GPL linking obligations, the codebase separates core and native backends into two independent Maven artifacts:

| Maven Coordinates (`build.gradle.kts`) | Package | Runtime Implementation | License & Dependencies |
| --- | --- | --- | --- |
| `io.github.cybernova2333:anabranch-core:0.1.0` | `io.anabranch.*` | Plain Java 8 (`HttpURLConnection`) | Zero third-party deps, zero `android.*`, `AGPL-3.0-only` (governed by root `ADDITIONAL_TERMS.zh-CN.md` / `ADDITIONAL_TERMS.en.md` / `ASSET_LICENSES.md`) |
| `io.github.cybernova2333:anabranch-aria2:0.1.0` (optional) | `io.anabranch.aria2.*` | Java glue + JNI `libaria2` `.so` | Depends on core; combined binary AAR is `GPL-3.0-or-later` |

> **Release status**: Coordinates and signing/bundling tasks exist in `build.gradle.kts`, while Maven Central publication is manual via `docs/RELEASING.md` and not yet live by default. Verify availability on Central or compile `src/` directly.
>
> ## Capabilities & Explicit Non-Goals
>
> - **What Anabranch Core Delivers**: Pure Java 8 (19 AGPL-3.0-only files, Android `minSdk 21` clean without desugaring) multi-mirror segmented transfer, failure-only retry budgets (`60s`), `.part` + `anabranch-meta/2` resume, SHA-256 verification, pluggable `Engine`/`Policy`/`Source.Resolver`, and an isolated GPL `anabranch-aria2` AAR.
> - **Explicit Non-Goals & Limits**: Strictly stateless library—never bundles mirror URLs, saved config, archive extraction, APK installers, UI, or foreground services; `Source.maxChunks` is metadata-only today; `Aria2Downloader` serializes globally under `BUSY` and verifies `.a2part` post-download.
>
> **Interactive Reference**: Browse every public Java class, extension SPI, and runnable snippet in the [API & Component Reference](/en/api-reference/).

## One-Minute Local Verification (No Gradle Required)

Daily development requires only a JDK supporting `javac --release 8`, Python 3, and a POSIX shell (Gradle wrapper is intentionally omitted):

```sh
sh tools/run-checks.sh
```

This script compiles both `src/io/anabranch/*.java` and `aria2/src/io/anabranch/aria2/*.java` with `--release 8`, enforces dependency isolation via `tools/check-core-boundaries.py`, and executes real loopback HTTP integration suites (`SingleSourceTest`, `MultiSourceTest`, `ThrottleTest`, and `Aria2FailoverTest`).

## Minimal Java / Android Call

```java
Downloader downloader = Downloader.newBuilder()
    .defaultChunks(8)
    .maxConcurrent(4)
    .bytesPerSecond(0) // <= 0 means unlimited
    .build();

SourceSet sources = new SourceSet(Arrays.asList(
    Source.ofBase("cdn-a", "https://cdn-a.example.test/releases/"),
    Source.ofBase("cdn-b", "https://cdn-b.example.test/releases/")),
    Policy.fastestFirst());

Result result = downloader.download(
    new Request("sdk.zip", new File(targetDir, "sdk.zip"), integrity, 8, true),
    sources,
    Listener.NONE,
    Cancellation.NONE);
```

Continue to [Integration, aria2 Build & FAQ](/en/docs/deployment) for builder options, Android constraints, and troubleshooting.
