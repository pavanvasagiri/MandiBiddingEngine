package com.mandi.model;

import java.math.BigDecimal;

final class OrderValidation {
    private OrderValidation() { }

    static void requireId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) {
            throw new IllegalArgumentException(field + " must be 1-100 URL-safe characters");
        }
    }

    static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0 || value.scale() > 4 || value.precision() > 18) {
            throw new IllegalArgumentException(field + " must be a positive decimal with at most 4 fractional digits");
        }
    }
}
