package com.mandi.model;

import java.util.Objects;

/** A farmer selling produce through the mandi. */
public final class Farmer {

    private volatile Integer farmerId; // assigned by the database (AUTO_INCREMENT)
    private final String name;
    private final String village;
    private final String bankAccountNo;

    public Farmer(String name, String village, String bankAccountNo) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("farmer name is required");
        if (village == null || village.isBlank()) throw new IllegalArgumentException("village is required");
        if (bankAccountNo == null || bankAccountNo.isBlank()) throw new IllegalArgumentException("bank account is required");
        this.name = name.trim();
        this.village = village.trim();
        this.bankAccountNo = bankAccountNo.trim();
    }

    public Integer getFarmerId() {
        return farmerId;
    }

    public void setFarmerId(Integer farmerId) {
        this.farmerId = Objects.requireNonNull(farmerId, "farmerId");
    }

    public String getName() {
        return name;
    }

    public String getVillage() {
        return village;
    }

    public String getBankAccountNo() {
        return bankAccountNo;
    }

    @Override
    public String toString() {
        return name + " (" + village + ")";
    }
}
