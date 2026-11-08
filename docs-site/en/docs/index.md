---
title: Quick Start
description: Anabranch Core powers speculative workflow branching and deterministic state coordination.
---

::: warning AI Translation Notice
This document was translated by an AI language model. In case of any discrepancies or ambiguities, please refer to the [original Chinese documentation](/docs/) as the authoritative reference.
:::

# Quick Start

Anabranch Core powers speculative workflow branching and deterministic state coordination.

## Core Problems Solved

- **Pain Point 1**: Linear state machines cannot efficiently explore exploratory agent branches without heavy state cloning.
- **Pain Point 2**: Rollback failures leading to dirty state contamination across exploratory paths.
- **Pain Point 3**: Excessive memory allocation and GC pauses during high-frequency branch evaluation.

---

## Prerequisites

Rust 1.80+ (Cargo), Node.js 20+, pnpm 12.6.0.

---

## Quick Installation & Launch

```bash
# 1. Install project dependencies
pnpm install

# 2. Run local tests and verification
pnpm test
```

---

## Next Steps

- Explore internal mechanics: [Architecture & Design](/en/docs/architecture)
- Comprehensive configuration reference: [Configuration Guide](/en/docs/configuration)
- Troubleshooting & FAQ: [FAQ & Diagnostics](/en/docs/faq)
