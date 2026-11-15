package com.example.weather.favourites;

import com.example.weather.provider.WeatherProvider;
import com.example.weather.weather.CityNames;
import com.example.weather.weather.Location;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Saved favourites of one client (ADR 0008/0009/0011).
 *
 * <p>Only {@link #create} calls the provider, and never inside a transaction: the duplicate and
 * limit checks run first (so a duplicate or over-limit create costs no upstream call), then
 * {@code locate}, then a short transaction that takes the client's advisory lock, repeats both
 * checks and inserts. The unique constraint is the last line of defence; its
 * {@link DuplicateKeyException} becomes a {@link FavouriteAlreadyExistsException}.
 *
 * <p>Inputs are already validated by the API layer. Nothing here logs client ids or labels.
 */
@Service
public class FavouriteService {

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final FavouriteRepository repository;
    private final WeatherProvider provider;
    private final TransactionOperations tx;
    private final Clock clock;
    private final FavouritesProperties properties;

    public FavouriteService(FavouriteRepository repository, WeatherProvider provider, TransactionOperations tx,
                            Clock clock, FavouritesProperties properties) {
        this.repository = repository;
        this.provider = provider;
        this.tx = tx;
        this.clock = clock;
        this.properties = properties;
    }

    /** {@code city} is stripped and validated; {@code label} is stripped or null. */
    public Favourite create(String clientId, String city, String label) {
        String key = CityNames.normalise(city);
        int max = properties.maxPerClient();
        checkCanAdd(clientId, key, max);
        Location location = provider.locate(city);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Favourite favourite = new Favourite(UUID.randomUUID(), clientId, city, label, location, 0, now, now);
        try {
            return tx.execute(status -> {
                repository.lockClient(clientId);
                checkCanAdd(clientId, key, max);
                repository.insert(favourite, key);
                return repository.find(clientId, favourite.id()).orElseThrow();
            });
        } catch (DuplicateKeyException e) {
            throw new FavouriteAlreadyExistsException();
        }
    }

    public List<Favourite> list(String clientId) {
        return repository.findAll(clientId);
    }

    public Favourite get(String clientId, UUID id) {
        return repository.find(clientId, id).orElseThrow(FavouriteNotFoundException::new);
    }

    /** Sets or clears ({@code null}) the label; one atomic statement. */
    public Favourite rename(String clientId, UUID id, String label) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        return repository.updateLabel(clientId, id, label, now).orElseThrow(FavouriteNotFoundException::new);
    }

    /**
     * Puts the client's favourites in the order of {@code ids}, which must list every favourite id
     * of the client exactly once. Every check runs before the first update; {@code updatedAt} is
     * not changed.
     */
    public List<Favourite> reorder(String clientId, List<String> ids) {
        List<UUID> ordered = parseIds(ids);
        return tx.execute(status -> {
            repository.lockClient(clientId);
            Set<UUID> existing = new HashSet<>(repository.findIds(clientId));
            if (!existing.equals(new HashSet<>(ordered))) {
                throw new InvalidFavouriteOrderException(InvalidFavouriteOrderException.SET_MISMATCH);
            }
            if (!ordered.isEmpty()) {
                repository.updatePositions(clientId, ordered);
            }
            return repository.findAll(clientId);
        });
    }

    public void delete(String clientId, UUID id) {
        Boolean deleted = tx.execute(status -> {
            repository.lockClient(clientId);
            return repository.delete(clientId, id);
        });
        if (!Boolean.TRUE.equals(deleted)) {
            throw new FavouriteNotFoundException();
        }
    }

    private void checkCanAdd(String clientId, String key, int max) {
        if (repository.existsByKey(clientId, key)) {
            throw new FavouriteAlreadyExistsException();
        }
        if (repository.count(clientId) >= max) {
            throw new FavouritesLimitReachedException(max);
        }
    }

    private static List<UUID> parseIds(List<String> ids) {
        if (ids == null) {
            throw new InvalidFavouriteOrderException(InvalidFavouriteOrderException.NOT_NULL);
        }
        List<UUID> parsed = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null || !CANONICAL_UUID.matcher(id).matches()) {
                throw new InvalidFavouriteOrderException(InvalidFavouriteOrderException.NOT_UUIDS);
            }
            parsed.add(UUID.fromString(id));
        }
        if (new HashSet<>(parsed).size() != parsed.size()) {
            throw new InvalidFavouriteOrderException(InvalidFavouriteOrderException.DUPLICATES);
        }
        return parsed;
    }
}
