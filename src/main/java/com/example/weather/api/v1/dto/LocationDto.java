package com.example.weather.api.v1.dto;

import com.example.weather.weather.Location;

public record LocationDto(String name, String country, double latitude, double longitude) {

    public static LocationDto from(Location l) {
        return new LocationDto(l.name(), l.country(), l.latitude(), l.longitude());
    }
}
