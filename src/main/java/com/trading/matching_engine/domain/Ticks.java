package com.trading.matching_engine.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Prices are integer ticks on the hot path, BigDecimal only at the API and database
 * boundaries. A BigDecimal key in the book means a heap allocation and a pointer-chasing
 * comparison on every tree navigation; a long key is a register compare.
 *
 * SCALE must match the NUMERIC(18,4) columns in schema.sql.
 */
public final class Ticks {
    public static final int SCALE = 4;

    private Ticks() {}

    /** Converts a wire/database price to ticks. Rejects prices finer than SCALE. */
    public static long fromDecimal(BigDecimal price) {
        return price.setScale(SCALE, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
    }

    public static BigDecimal toDecimal(long ticks) {
        return BigDecimal.valueOf(ticks, SCALE);
    }

    /** True when the price can be represented exactly at SCALE — used for validation. */
    public static boolean isRepresentable(BigDecimal price) {
        try {
            fromDecimal(price);
            return true;
        } catch (ArithmeticException e) {
            return false;
        }
    }
}
