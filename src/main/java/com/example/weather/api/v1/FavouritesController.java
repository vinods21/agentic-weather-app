package com.example.weather.api.v1;

import com.example.weather.api.ClientId;
import com.example.weather.api.v1.dto.FavouriteListResponse;
import com.example.weather.api.v1.dto.FavouriteResponse;
import com.example.weather.favourites.Favourite;
import com.example.weather.favourites.FavouriteService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved favourites of the v1 API (ADR 0009/0010). {@link ClientId} is always the first parameter,
 * so the {@code X-Client-Id} header is validated before the path id and the body. Parameters carry
 * no constraint annotations, so Spring's method validation stays inactive (ADR 0010 §2). Errors
 * are mapped by {@code ApiExceptionHandler}.
 */
@RestController
@RequestMapping(value = FavouritesController.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
public class FavouritesController {

    static final String BASE = "/api/v1/favourites";

    private final FavouriteService service;

    public FavouritesController(FavouriteService service) {
        this.service = service;
    }

    @GetMapping
    public FavouriteListResponse list(ClientId clientId) {
        return FavouriteListResponse.from(service.list(clientId.value()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FavouriteResponse> create(ClientId clientId,
                                                    @Valid @RequestBody CreateFavouriteRequest request) {
        Favourite created = service.create(clientId.value(), request.city(), request.label());
        return ResponseEntity.created(URI.create(BASE + "/" + created.id())).body(FavouriteResponse.from(created));
    }

    @GetMapping("/{id}")
    public FavouriteResponse get(ClientId clientId, @PathVariable UUID id) {
        return FavouriteResponse.from(service.get(clientId.value(), id));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FavouriteResponse rename(ClientId clientId, @PathVariable UUID id,
                                    @Valid @RequestBody UpdateFavouriteRequest request) {
        return FavouriteResponse.from(service.rename(clientId.value(), id, request.label()));
    }

    @PutMapping(value = "/order", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FavouriteListResponse reorder(ClientId clientId, @Valid @RequestBody ReorderFavouritesRequest request) {
        return FavouriteListResponse.from(service.reorder(clientId.value(), request.ids()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(ClientId clientId, @PathVariable UUID id) {
        service.delete(clientId.value(), id);
        return ResponseEntity.noContent().build();
    }
}
