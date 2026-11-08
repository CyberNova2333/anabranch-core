---
title: Configuration Guide
description: Anabranch configuration settings, memory limits, and branch merge policies.
---

::: warning AI Translation Notice
This document was translated by an AI language model. In case of any discrepancies or ambiguities, please refer to the [original Chinese documentation](/docs/configuration) as the authoritative reference.
:::

# Configuration Guide

### Primary Configuration

```json
[engine]
max_concurrent_branches = 64
branch_depth_limit = 16
copy_on_write_strategy = "chunked"

[speculation]
prune_failed_branches = true
auto_compact_interval_ms = 5000
```

---

## Production Recommendations

1. **Environment Variable Protection**: Never commit real API keys or sensitive credentials into Git repositories.
2. **Graceful Shutdown & Timeouts**: Set appropriate timeout constraints on long-running processes to prevent orphan executions.
3. **Continuous Monitoring**: Combine console logs and health telemetry to monitor service uptime and operational status.
