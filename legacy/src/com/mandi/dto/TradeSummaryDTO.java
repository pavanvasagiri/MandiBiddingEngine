package com.mandi.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TradeSummaryDTO(
    String lotId,
    String buyerId,
    String farmerId,
    BigDecimal grossAmount,
    BigDecimal mandiTax,
    BigDecimal netPayout,
    LocalDateTime settlementTimestamp
) {
}
