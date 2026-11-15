package com.example.weather.api.v1;

import com.example.weather.api.ClientId;
import com.example.weather.api.v1.dto.FavouriteWeatherListResponse;
import com.example.weather.favourites.FavouriteWeatherService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/favourites/weather}: current weather for every favourite of the client
 * (ADR 0012/0013/0014). {@link ClientId} is the first parameter, so the {@code X-Client-Id} header
 * is validated before the query. Parameters carry no constraint annotations (ADR 0010 §2). The
 * literal {@code /weather} pattern takes precedence over {@code FavouritesController}'s {@code /{id}}.
 */
@RestController
@RequestMapping(value = FavouritesController.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
public class FavouritesWeatherController {

    private final FavouriteWeatherService service;

    public FavouritesWeatherController(FavouriteWeatherService service) {
        this.service = service;
    }

    @GetMapping("/weather")
    public FavouriteWeatherListResponse weather(ClientId clientId, @Valid @ModelAttribute FavouritesWeatherQuery query) {
        return FavouriteWeatherListResponse.from(service.currentForAll(clientId.value(), query.resolvedUnits()));
    }
}
