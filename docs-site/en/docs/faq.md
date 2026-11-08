---
title: FAQ & Troubleshooting
description: Frequently asked questions and debugging guidelines for Anabranch Core.
---

::: warning AI Translation Notice
This document was translated by an AI language model. In case of any discrepancies or ambiguities, please refer to the [original Chinese documentation](/docs/faq) as the authoritative reference.
:::

# FAQ & Troubleshooting

Common questions and answers regarding daily usage and integration:

### Q: Can Anabranch Core run embedded in WebAssembly?

**A**: Yes. The core crate is `no_std`-friendly and compiles seamlessly to wasm32-unknown-unknown targets.

### Q: How does state isolation work between branches?

**A**: Branches share immutable structural history and copy-on-write page frames, ensuring total isolation with minimal memory footprint.


---

## General Troubleshooting Steps

1. **Verify Runtime Versions**: Ensure Node.js, Python, Rust, or browser versions meet minimum compatibility requirements.
2. **Check Diagnostic Logs**: Review console output and error logs for informative stack traces and line numbers.
3. **Submit Issue Feedback**: If you discover an underlying bug, please open an issue on the GitHub repository with a reproducible test case.
