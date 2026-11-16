---
layout: page
sidebar: false
title: API & Component Reference
description: Interactive Java 8 SDK & SPI reference for Anabranch Core (Downloader, Request, Integrity, Engine, Policy, Source.Resolver, Aria2Downloader).
---

<script setup lang="ts">
import { apiSpecEn } from '../../.vitepress/data/api-spec'
</script>

<ApiExplorer :spec="apiSpecEn">

### `Downloader`, `Request` & `Integrity` Contracts (`Downloader.download`) {#java-downloader-request}

Zero-dependency Java 8 core (19 files, `minSdk 21` clean) with multi-mirror byte-range orchestration, 60s failure budget, `.part` + `.meta` resume, and SHA-256 verification.

### Custom `Engine`, `Policy` & `Source.Resolver` SPIs {#ext-engine-policy}

Pluggable transport and routing policies guarded by `tools/check-core-boundaries.py`.

### Optional Isolated `aria2` JNI Backend (`Aria2Downloader`) {#aria2-jni-backend}

Separate `GPL-3.0-or-later` AAR wrapping `libaria2` with serialized `runOnce` scheduling and post-download integrity checks.

</ApiExplorer>
