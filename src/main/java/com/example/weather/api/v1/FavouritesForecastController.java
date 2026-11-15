package com.example.weather.api.v1;

import com.example.weather.api.ClientId;
import com.example.weather.api.v1.dto.FavouriteForecastListResponse;
import com.example.weather.favourites.FavouriteForecastService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/favourites/forecast}: daily forecast for every favourite of the client
 * (ADR 0014/0015/0016). {@link ClientId} is the first parameter, so the {@code X-Client-Id} header
 * is validated before the query. Parameters carry no constraint annotations (ADR 0010 §2). The
 * literal {@code /forecast} pattern takes precedence over {@code FavouritesController}'s {@code /{id}}.
 */
@RestController
@RequestMapping(value = FavouritesController.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
public class FavouritesForecastController {

    private final FavouriteForecastService service;

    public FavouritesForecastController(FavouriteForecastService service) {
        this.service = service;
    }

    @GetMapping("/forecast")
    public FavouriteForecastListResponse forecast(ClientId clientId,
                                                  @Valid @ModelAttribute FavouritesForecastQuery query) {
        return FavouriteForecastListResponse.from(
                service.forecastForAll(clientId.value(), query.resolvedUnits(), query.resolvedDays()));
    }
}
