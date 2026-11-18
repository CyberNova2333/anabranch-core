// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Anabranch Core and is licensed under GNU AGPLv3 with Additional Terms (see LICENSE and LICENSE-ADDITIONAL_TERMS-zh-CN.md; ref: LICENSE-ADDITIONAL_TERMS-en.md).
package io.anabranch;

/** 日志。默认 no-op——库不该强迫使用者接受某一套日志设施。 */
public interface Logger {
    void log(String msg);
    void log(String msg, Throwable t);
    Logger NONE = new Logger() {
        public void log(String m) {}
        public void log(String m, Throwable t) {}
    };
}
