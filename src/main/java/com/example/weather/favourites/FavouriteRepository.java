package com.example.weather.favourites;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for favourites (ADR 0008 §2). Every method is scoped to one client id; a row of
 * another client is never read or changed.
 */
public interface FavouriteRepository {

    /** First key of the per-client {@code pg_advisory_xact_lock(int, int)} ("FAV1"). */
    int LOCK_NAMESPACE = 0x46415631;

    /** The client's favourites in list order. */
    List<Favourite> findAll(String clientId);

    Optional<Favourite> find(String clientId, UUID id);

    boolean existsByKey(String clientId, String cityKey);

    int count(String clientId);

    /** Takes the client's transaction-scoped advisory lock; must run inside a transaction. */
    void lockClient(String clientId);

    /** Inserts {@code favourite} at the end of the client's list (its {@code position} is ignored). */
    void insert(Favourite favourite, String cityKey);

    /** Sets the label and {@code updatedAt}; empty if the client has no favourite with this id. */
    Optional<Favourite> updateLabel(String clientId, UUID id, String label, Instant updatedAt);

    List<UUID> findIds(String clientId);

    /** Sets positions 1..n in the given order. */
    void updatePositions(String clientId, List<UUID> orderedIds);

    /** True if a row was deleted. */
    boolean delete(String clientId, UUID id);
}
