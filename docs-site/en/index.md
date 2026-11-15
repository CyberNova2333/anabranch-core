---
layout: home

hero:
  name: Anabranch Core
  text: Multi-Source Segmented Downloader for Java 8 & Android
  tagline: Zero-dependency pure Java 8 core compatible with Android minSdk 21—parallel multi-mirror chunking, failover, resumable state, and per-chunk SHA-256 verification, plus an optional standalone aria2 JNI backend.
  actions:
    - theme: brand
      text: Getting Started
      link: /en/docs/
    - theme: alt
      text: Architecture & Source
      link: /en/docs/architecture
    - theme: alt
      text: GitHub Repository
      link: https://github.com/CyberNova2333/anabranch-core

features:
  - title: Multi-Mirror Chunk Scheduling
    details: Splits a single file across mirrors using Policy.fastestFirst() (EMA throughput vs. in-flight load) or Policy.weighted().
  - title: Failover & Retry Budget
    details: Caps each chunk at 6 attempts, up to 2000ms backoff, and a 60s chunkRetryBudgetMs counting only failures—not healthy slow transfers.
  - title: Resumable State & Digest Guard
    details: Persists .part and anabranch-meta/2 (.meta) with P/D/V states, same-source ETag checks, and per-chunk + whole-file SHA-256 before rename.
  - title: Clean Artifact & License Isolation
    details: Core has 19 Java 8 files (AGPL-3.0-only, zero android.* or Java 9+ APIs). Optional aria2 JNI backend ships as a separate GPL-3.0-or-later AAR.
---

## Quick Jump by Role & Goal

| Role / Goal | Recommended Page | What You Get |
| --- | --- | --- |
| **App Integrator (JVM / Android)** | [Overview & Quick Start](/en/docs/) | Compare `anabranch-core` vs `anabranch-aria2`, check coordinates, and run `tools/run-checks.sh` |
| **Build & Deployment Engineer** | [Integration, aria2 Build & FAQ](/en/docs/deployment) | Inspect defaults, `minSdk 21` no-desugaring rules, NDK/host aria2 builds, and troubleshooting |
| **Extension Developer** | [Public API & Extension Points](/en/docs/contracts) | Implement custom `Engine` (e.g., OkHttp), `Policy`, `Source.Resolver`, and compare `Aria2Downloader` |
| **Protocol & Reliability Reviewer** | [Scheduling, Resume & Integrity](/en/docs/mechanisms) | Review EMA scoring, `.meta` v2 layout, commit ordering, and TLS dead-man's switch |
| **Code Maintainer & Release Owner** | [Architecture](/en/docs/architecture) · [Code Map](/en/docs/code-map) | Trace one run end-to-end, map classes/tests, and check [Design Decisions & Releasing](/en/docs/decisions) |

## Minimal Usage

```java
Downloader dl = Downloader.newBuilder()
    .defaultChunks(8)
    .maxConcurrent(4)
    .chunkRetryBudgetMs(60_000)
    .build();

SourceSet sources = new SourceSet(Arrays.asList(
    Source.ofBase("cdn-a", "https://cdn-a.example.test/pkg/"),
    Source.ofBase("cdn-b", "https://cdn-b.example.test/pkg/")),
    Policy.fastestFirst());

Result res = dl.download(
    new Request("app.bin", new File("out/app.bin"), integrity, 8, true),
    sources, Listener.NONE, Cancellation.NONE);
```
