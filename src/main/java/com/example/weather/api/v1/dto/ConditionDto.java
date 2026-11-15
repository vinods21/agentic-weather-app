package com.example.weather.api.v1.dto;

import com.example.weather.weather.Condition;
import com.example.weather.weather.ConditionCode;

public record ConditionDto(ConditionCode code, String description) {

    public static ConditionDto from(Condition c) {
        return new ConditionDto(c.code(), c.description());
    }
}
