# Anabranch · Multi-source chunked downloads

> 中文版（主文档）：[README.md](README.md)
>
> **anabranch**: a stretch of river that splits off the main channel and rejoins it
> downstream. Data flows in several strands over different routes and merges back
> into one file — the name is literal, not a metaphor.

One file, N mirrors, scheduled per chunk: which source serves a given chunk is
decided at runtime from measured throughput; when a source fails, only that chunk
is re-fetched from another source; every chunk can be verified against a digest.

Pure Java, zero third-party dependencies, and the core never touches Android —
which is why the full test suite runs on a desktop JVM.

## Why another download library

| capability | system DownloadManager | typical chunked downloaders | anabranch |
|---|---|---|---|
| multi-threaded chunking | ❌ | ✅ | ✅ |
| **multiple sources for one file, in parallel** | ❌ | ❌ | ✅ |
| **per-chunk failover between sources** | ❌ | ❌ | ✅ |
| **source racing with measured throughput** | ❌ | ❌ | ✅ |
| per-chunk verification | ❌ | ❌ | ✅ |
| swappable engine (native backends possible) | ❌ | ❌ | ✅ |

The differentiator is one word: **multi-source**. Multi-threaded chunking is a
mature category; this library exists for "one file, N routes, per-chunk
scheduling".

## Coordinates

Planned for Maven Central (not yet published):

```
io.anabranch:anabranch-core:0.1.0
```

Requires Java 8+ bytecode support; on Android, minSdk 21. No runtime library
from API 24+ is used (no `java.util.function`, `stream`, `Optional`,
`nio.file`, `time`), so no core-library desugaring is required.

## Quick look

```java
Downloader dl = Downloader.newBuilder().build();

SourceSet mirrors = new SourceSet(Arrays.asList(
        Source.ofBase("cdn",    "https://cdn.example.com/files/"),
        Source.ofBase("mirror", "https://mirror.example.org/files/")),
        Policy.fastestFirst());

Result r = dl.download(
        Request.of("app-data.zip", new File(dir, "app-data.zip")),
        mirrors,
        Listener.NONE,
        Cancellation.NONE);
```

- `Source.Resolver` maps a logical key to a URL per source, so mirrors with
  different paths, signed URLs, or token-carrying CDNs all work.
- `Integrity` carries an optional whole-file SHA-256 and optional per-chunk
  digests. Per-chunk digests are what make failover safe: without them there is
  no way to prove that a file assembled from several sources is correct.
- `Downloader.probeAll()` checks that all mirrors agree on size before you spend
  a whole download finding out that one of them still serves an old version.

## Design in one diagram

```
Downloader   (chunking / concurrency / retry loop / verification / commit)
    ├── Policy + Stats   (who serves the next chunk; what happens on failure)
    └── Engine           (fetches exactly one byte range; nothing else)
```

- **Engine implementations must not reference the scheduling layer.** This
  boundary is what makes native backends (e.g. aria2) possible without
  replacing the retry/failover logic.
- **Policy reads Stats and never does IO**, so scheduling rules are unit-tested
  against fake stats instead of racing real servers.

## What the tests actually cover

All three suites start real local HTTP servers (`com.sun.net.httpserver`) and
transfer real bytes — the failure modes this library defends against (a server
sending fewer bytes than promised, mirrors serving different versions, a
connection that accepts and then goes silent) simply do not happen in mocks.
The multi-source suite runs three mirrors that can be switched at any moment to
"dead / slow / fail after N ranges / stall without sending a byte".

Highlights, each guarding a silent-corruption path:

- a mid-transfer disconnect throws instead of "succeeding" with half a chunk;
- resume state survives failures, including **resuming across sources** — etags
  legitimately differ between mirrors, so the etag is only compared against the
  source that produced it (correctness is backed by per-chunk digests);
- a chunk that fails verification is re-fetched, never committed;
- a stalled source is bounded by a **time budget** (`chunkRetryBudgetMs`), not
  just an attempt cap — a cap alone multiplies the read-timeout cost;
- retries back off exponentially instead of hammering a struggling server;
- rate limiting (`bytesPerSecond`) applies to the whole download, not per chunk,
  and is enforced on the write side so it works with any `Engine`.

## Documentation

Most documentation is in Chinese — [README.md](README.md) is the primary and
more detailed version of this file; [docs/DESIGN.md](docs/DESIGN.md) holds the
full design; [CONTRIBUTING.md](CONTRIBUTING.md) covers development workflow.

Development needs no build system:

```bash
sh tools/run-checks.sh    # compile + core boundary checks + 77 tests, ~1 minute
```

Gradle exists solely for packaging and publishing (see
[docs/RELEASING.md](docs/RELEASING.md)).

## License

Apache-2.0. A future `anabranch-aria2` backend must be a **separate artifact**:
aria2 is GPLv2+, and statically linking it creates source-provision obligations
that pure-Java users should not inherit.
