package com.quanlybanhang.util;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Add lossless decimal strings without changing existing numeric API fields. */
public final class ExactAmounts {
    private ExactAmounts() {}
    public static Map<String, String> of(Object... pairs) {
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            var value = (BigDecimal) pairs[i + 1];
            result.put((String) pairs[i], value == null ? null : value.toPlainString());
        }
        return result;
    }
}
