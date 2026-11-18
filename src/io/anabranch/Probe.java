// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Anabranch Core and is licensed under GNU AGPLv3 with Additional Terms (see LICENSE and ADDITIONAL_TERMS.zh-CN.md; ref: ADDITIONAL_TERMS.en.md).
package io.anabranch;

/** 探测结果：总长度、etag、是否支持 Range。 */
public final class Probe {
    public final long total;
    public final String etag;
    public final boolean rangeSupported;

    public Probe(long total, String etag, boolean rangeSupported) {
        this.total = total;
        this.etag = etag == null ? "" : etag.trim();
        this.rangeSupported = rangeSupported;
    }

    @Override public String toString() {
        return "Probe{total=" + total + ", etag='" + etag
                + "', range=" + rangeSupported + "}";
    }
}
