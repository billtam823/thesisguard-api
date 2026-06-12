package com.thesisguard.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.thesisguard.common.exception.BadRequestException;

public enum ThesisChangeLevel {
    No_Change("No Change"),
    Minor_Change("Minor Change"),
    Watch_Change("Watch Change"),
    Material_Change("Material Change"),
    Thesis_Broken("Thesis Broken"),
    No_News_Found("No News Found");

    private final String label;

    ThesisChangeLevel(String label) {
        this.label = label;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }

    @JsonCreator
    public static ThesisChangeLevel from(String value) {
        for (ThesisChangeLevel level : values()) {
            if (level.label.equalsIgnoreCase(value) || level.name().equalsIgnoreCase(value)) {
                return level;
            }
        }
        throw new BadRequestException("Invalid thesis_change_level: " + value);
    }
}
