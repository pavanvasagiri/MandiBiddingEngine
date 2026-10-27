package com.mandi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A tradable crop and its government-notified Minimum Support Price (MSP).
 * The engine rejects any buyer bid priced below the registered MSP.
 */
public final class Crop {

    private volatile Integer cropId; // assigned by the database (AUTO_INCREMENT)
    private final String cropName;
    private final BigDecimal mspPricePerQuintal;

    public Crop(String cropName, BigDecimal mspPricePerQuintal) {
        if (cropName == null || cropName.isBlank()) {
            throw new IllegalArgumentException("cropName is required");
        }
        if (mspPricePerQuintal == null || mspPricePerQuintal.signum() <= 0) {
            throw new IllegalArgumentException("MSP must be a positive amount");
        }
        this.cropName = cropName.trim();
        this.mspPricePerQuintal = mspPricePerQuintal.setScale(2, RoundingMode.HALF_UP);
    }

    public Integer getCropId() {
        return cropId;
    }

    public void setCropId(Integer cropId) {
        this.cropId = Objects.requireNonNull(cropId, "cropId");
    }

    public String getCropName() {
        return cropName;
    }

    public BigDecimal getMspPricePerQuintal() {
        return mspPricePerQuintal;
    }

    @Override
    public String toString() {
        return cropName + " (MSP Rs." + mspPricePerQuintal + "/quintal)";
    }
}
