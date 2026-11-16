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
      text: Public API & Contracts
      link: /en/docs/contracts
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

## Architecture Overview

<div class="archify-diagram-card" style="margin: 1.5rem 0; border: 1px solid var(--vp-c-border); border-radius: 12px; overflow: hidden; background: var(--vp-c-bg-soft); box-shadow: 0 4px 16px rgba(0,0,0,0.06);">
  <div style="display: flex; justify-content: space-between; align-items: center; padding: 10px 18px; background: var(--vp-c-bg-elv); border-bottom: 1px solid var(--vp-c-border); font-size: 13px;">
    <span style="font-weight: 600; color: var(--vp-c-text-1); display: flex; align-items: center; gap: 8px;">
      <span style="font-size: 16px;">📐</span> Multi-Source Chunk Scheduling & State Persistence Topology (pan / zoom / inspect)
    </span>
    <a href="/diagrams/anabranch-architecture.html" target="_blank" rel="noreferrer" style="font-size: 12px; color: var(--vp-c-brand-1); text-decoration: none; font-weight: 500;">
      Open Fullscreen ↗
    </a>
  </div>
  <iframe src="/diagrams/anabranch-architecture.html" width="100%" height="520px" frameborder="0" style="display: block; border: none;" allowfullscreen loading="lazy"></iframe>
</div>

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

## Documentation Guide

::: tip Quick Navigation by Role & Goal
- **App Integrator (JVM / Android)**: Start with [Overview & Quick Start](/en/docs/) to compare `anabranch-core` vs `anabranch-aria2`, check coordinates, and run `tools/run-checks.sh`;
- **Build & Deployment Engineer**: Read [Integration, aria2 Build & FAQ](/en/docs/deployment) for defaults, `minSdk 21` no-desugaring rules, NDK/host aria2 builds, and troubleshooting;
- **Extension Developer**: See [Public API & Extension Points](/en/docs/contracts) and [Scheduling, Resume & Integrity](/en/docs/mechanisms) to implement custom `Engine` (e.g., OkHttp), `Policy`, and `Source.Resolver`;
- **Code Maintainer & Release Owner**: Explore [Architecture & One Real Run](/en/docs/architecture), [Code Map](/en/docs/code-map), and [Design Decisions & Releasing](/en/docs/decisions).
:::
