package com.thesisguard.stock;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.thesisguard.common.exception.BadRequestException;

public enum StockStatus {
    Hold("Hold"),
    Watch("Watch"),
    Reduce_Review("Reduce Review"),
    Sell_Review("Sell Review");

    private final String label;

    StockStatus(String label) {
        this.label = label;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }

    @JsonCreator
    public static StockStatus from(String value) {
        for (StockStatus status : values()) {
            if (status.label.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new BadRequestException("Invalid stock status: " + value);
    }
}
