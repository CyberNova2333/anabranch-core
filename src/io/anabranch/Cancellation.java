// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Anabranch Core and is licensed under GNU AGPLv3 with Additional Terms (see LICENSE and LICENSE-ADDITIONAL_TERMS-zh-CN.md; ref: LICENSE-ADDITIONAL_TERMS-en.md).
package io.anabranch;

/** 取消信号。与进度回调分开——只想看进度的调用方不该被迫实现取消。 */
public interface Cancellation {
    boolean isCancelled();
    Cancellation NONE = () -> false;
}
