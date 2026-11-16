---
layout: page
sidebar: false
title: API 与组件参考
description: Anabranch Core 纯 Java 8 多源分块下载 SDK（Downloader / Request / Integrity / Policy / Engine）与可选 aria2 JNI 后端参考。
---

<script setup lang="ts">
import { apiSpecZh } from '../.vitepress/data/api-spec'
</script>

<ApiExplorer :spec="apiSpecZh">

### `Downloader` 协调器与 `Request` / `Integrity` 模型 (`Downloader.download`) {#java-downloader-request}

19 个纯 Java 8 文件（兼容 Android `minSdk 21` 无需 desugaring），支持多镜像分块调度、60 秒纯失败重试预算与 `.part` + `anabranch-meta/2` 断点校验。

### 自定义 `Engine`、`Policy` 与 `Source.Resolver` 扩展契约 {#ext-engine-policy}

支持接入 OkHttp 或自定义 EMA 选源与失败决策（`RETRY_SAME`/`SWITCH_SOURCE`/`DISABLE_SOURCE`/`ABORT`），受向内依赖边界脚本守卫。

### 可选独立 `aria2` JNI 后端 (`Aria2Downloader`) {#aria2-jni-backend}

以独立 `GPL-3.0-or-later` AAR 封装原生 `libaria2` 调度，单线程串行执行并在下载结束后校验 `.a2part`。

</ApiExplorer>
