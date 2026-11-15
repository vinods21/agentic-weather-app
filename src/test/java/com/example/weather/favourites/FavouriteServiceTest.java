package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProvider;
import com.example.weather.weather.Location;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

/** Unit test: mocked repository and provider, no transaction manager, fixed clock. No Spring, no Docker. */
class FavouriteServiceTest {

    private static final String CLIENT = "client-1";
    private static final Location LONDON = new Location("London", "GB", 51.5074, -0.1278);
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00.789Z");
    private static final Instant NOW_SECONDS = Instant.parse("2026-11-15T10:00:00Z");

    private final FavouriteRepository repository = mock(FavouriteRepository.class);
    private final WeatherProvider provider = mock(WeatherProvider.class);
    private FavouriteService service;

    @BeforeEach
    void setUp() {
        service = new FavouriteService(repository, provider, TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC), new FavouritesProperties(3));
    }

    private static Favourite favourite(UUID id) {
        return new Favourite(id, CLIENT, "London", null, LONDON, 1, NOW_SECONDS, NOW_SECONDS);
    }

    private void stubInsertedRowIsFound() {
        when(repository.find(eq(CLIENT), any(UUID.class)))
                .thenAnswer(inv -> Optional.of(favourite(inv.getArgument(1))));
    }

    @Test
    @DisplayName("AC-2: create locates the city as sent, inserts under the lock with second-precision timestamps")
    void createHappyPath() {
        when(provider.locate("  New   York")).thenReturn(LONDON);
        stubInsertedRowIsFound();

        Favourite result = service.create(CLIENT, "  New   York", "Home");

        ArgumentCaptor<Favourite> inserted = ArgumentCaptor.forClass(Favourite.class);
        InOrder order = inOrder(repository, provider);
        order.verify(repository).existsByKey(CLIENT, "new york");
        order.verify(repository).count(CLIENT);
        order.verify(provider).locate("  New   York");
        order.verify(repository).lockClient(CLIENT);
        order.verify(repository).existsByKey(CLIENT, "new york");
        order.verify(repository).count(CLIENT);
        order.verify(repository).insert(inserted.capture(), eq("new york"));
        Favourite f = inserted.getValue();
        assertThat(f.clientId()).isEqualTo(CLIENT);
        assertThat(f.city()).isEqualTo("  New   York");
        assertThat(f.label()).isEqualTo("Home");
        assertThat(f.location()).isEqualTo(LONDON);
        assertThat(f.createdAt()).isEqualTo(NOW_SECONDS);
        assertThat(f.updatedAt()).isEqualTo(NOW_SECONDS);
        assertThat(result.id()).isEqualTo(f.id());
    }

    @Test
    @DisplayName("AC-9: a duplicate is rejected before the provider is called")
    void duplicateBeforeProvider() {
        when(repository.existsByKey(CLIENT, "london")).thenReturn(true);

        assertThatThrownBy(() -> service.create(CLIENT, "LONDON", null))
                .isInstanceOf(FavouriteAlreadyExistsException.class);
        verifyNoInteractions(provider);
        verify(repository, never()).insert(any(), anyString());
    }

    @Test
    @DisplayName("AC-13: the limit is checked before the provider is called; the message carries the max")
    void limitBeforeProvider() {
        when(repository.count(CLIENT)).thenReturn(3);

        assertThatThrownBy(() -> service.create(CLIENT, "Sydney", null))
                .isInstanceOfSatisfying(FavouritesLimitReachedException.class, e -> {
                    assertThat(e.max()).isEqualTo(3);
                    assertThat(e.getMessage()).isEqualTo("This client already has the maximum of 3 favourites.");
                });
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("AC-13: a duplicate that is also over the limit is a duplicate")
    void duplicateWinsOverLimit() {
        when(repository.existsByKey(CLIENT, "london")).thenReturn(true);
        when(repository.count(CLIENT)).thenReturn(3);

        assertThatThrownBy(() -> service.create(CLIENT, "London", null))
                .isInstanceOf(FavouriteAlreadyExistsException.class);
    }

    @Test
    @DisplayName("AC-12: a duplicate found again under the lock is a 409, nothing is inserted")
    void duplicateUnderLock() {
        when(repository.existsByKey(CLIENT, "london")).thenReturn(false, true);
        when(provider.locate("London")).thenReturn(LONDON);

        assertThatThrownBy(() -> service.create(CLIENT, "London", null))
                .isInstanceOf(FavouriteAlreadyExistsException.class);
        verify(repository).lockClient(CLIENT);
        verify(repository, never()).insert(any(), anyString());
    }

    @Test
    @DisplayName("AC-13: the limit is re-checked under the lock")
    void limitUnderLock() {
        when(repository.count(CLIENT)).thenReturn(2, 3);
        when(provider.locate("Sydney")).thenReturn(LONDON);

        assertThatThrownBy(() -> service.create(CLIENT, "Sydney", null))
                .isInstanceOf(FavouritesLimitReachedException.class);
        verify(repository, never()).insert(any(), anyString());
    }

    @Test
    @DisplayName("AC-12: a unique-constraint violation on insert becomes FavouriteAlreadyExistsException")
    void duplicateKeyMapped() {
        when(provider.locate("London")).thenReturn(LONDON);
        doThrow(new DuplicateKeyException("23505")).when(repository).insert(any(), anyString());

        assertThatThrownBy(() -> service.create(CLIENT, "London", null))
                .isInstanceOf(FavouriteAlreadyExistsException.class)
                .hasNoCause();
    }

    @Test
    @DisplayName("AC-7: a not-found city propagates and nothing is inserted")
    void notFoundPropagates() {
        when(provider.locate("Atlantis")).thenThrow(new LocationNotFoundException("Atlantis"));

        assertThatThrownBy(() -> service.create(CLIENT, "Atlantis", null))
                .isInstanceOf(LocationNotFoundException.class);
        verify(repository, never()).lockClient(anyString());
        verify(repository, never()).insert(any(), anyString());
    }

    @Test
    @DisplayName("AC-20: get of a missing id is FavouriteNotFoundException")
    void getMissing() {
        UUID id = UUID.randomUUID();
        when(repository.find(CLIENT, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(CLIENT, id))
                .isInstanceOf(FavouriteNotFoundException.class)
                .hasMessage("No favourite exists with this id.");
    }

    @Test
    @DisplayName("AC-21: rename sets the label with a second-precision updatedAt; missing id is not found")
    void rename() {
        UUID id = UUID.randomUUID();
        when(repository.updateLabel(CLIENT, id, "Work", NOW_SECONDS)).thenReturn(Optional.of(favourite(id)));

        assertThat(service.rename(CLIENT, id, "Work").id()).isEqualTo(id);
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> service.rename(CLIENT, other, null)).isInstanceOf(FavouriteNotFoundException.class);
    }

    @Test
    @DisplayName("AC-23: reorder writes positions in the given order under the lock")
    void reorder() {
        UUID l = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        UUID t = UUID.randomUUID();
        when(repository.findIds(CLIENT)).thenReturn(List.of(l, p, t));
        when(repository.findAll(CLIENT)).thenReturn(List.of(favourite(t), favourite(l), favourite(p)));

        List<Favourite> result = service.reorder(CLIENT, List.of(t.toString(), l.toString().toUpperCase(), p.toString()));

        InOrder order = inOrder(repository);
        order.verify(repository).lockClient(CLIENT);
        order.verify(repository).findIds(CLIENT);
        order.verify(repository).updatePositions(CLIENT, List.of(t, l, p));
        order.verify(repository).findAll(CLIENT);
        assertThat(result).extracting(Favourite::id).containsExactly(t, l, p);
    }

    @Test
    @DisplayName("AC-23: an empty list with no favourites is accepted")
    void reorderEmpty() {
        when(repository.findIds(CLIENT)).thenReturn(List.of());
        when(repository.findAll(CLIENT)).thenReturn(List.of());

        assertThat(service.reorder(CLIENT, List.of())).isEmpty();
        verify(repository, never()).updatePositions(anyString(), anyList());
    }

    @Test
    @DisplayName("AC-23: each invalid reorder gives its fixed message and never updates positions")
    void reorderFailures() {
        UUID l = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        UUID t = UUID.randomUUID();
        when(repository.findIds(CLIENT)).thenReturn(List.of(l, p, t));

        assertReorderFails(null, InvalidFavouriteOrderException.NOT_NULL);
        assertReorderFails(List.of(t.toString(), "not-a-uuid"), InvalidFavouriteOrderException.NOT_UUIDS);
        assertReorderFails(List.of("1-1-1-1-1"), InvalidFavouriteOrderException.NOT_UUIDS);
        assertReorderFails(Arrays.asList(t.toString(), null), InvalidFavouriteOrderException.NOT_UUIDS);
        assertReorderFails(List.of(t.toString(), t.toString(), l.toString(), p.toString()),
                InvalidFavouriteOrderException.DUPLICATES);
        assertReorderFails(List.of(t.toString(), l.toString()), InvalidFavouriteOrderException.SET_MISMATCH);
        assertReorderFails(List.of(t.toString(), l.toString(), p.toString(), UUID.randomUUID().toString()),
                InvalidFavouriteOrderException.SET_MISMATCH);
        assertReorderFails(List.of(), InvalidFavouriteOrderException.SET_MISMATCH);

        verify(repository, never()).updatePositions(anyString(), anyList());
    }

    private void assertReorderFails(List<String> ids, String message) {
        assertThatThrownBy(() -> service.reorder(CLIENT, ids))
                .isInstanceOf(InvalidFavouriteOrderException.class)
                .hasMessage(message);
    }

    @Test
    @DisplayName("AC-25: delete takes the lock; a missing id is not found")
    void delete() {
        UUID id = UUID.randomUUID();
        when(repository.delete(CLIENT, id)).thenReturn(true);

        service.delete(CLIENT, id);
        InOrder order = inOrder(repository);
        order.verify(repository).lockClient(CLIENT);
        order.verify(repository).delete(CLIENT, id);

        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> service.delete(CLIENT, other)).isInstanceOf(FavouriteNotFoundException.class);
    }

    @Test
    @DisplayName("AC-19: list, get, rename, reorder and delete never call the provider")
    void onlyCreateUsesProvider() {
        UUID id = UUID.randomUUID();
        when(repository.find(CLIENT, id)).thenReturn(Optional.of(favourite(id)));
        when(repository.updateLabel(eq(CLIENT), eq(id), any(), any())).thenReturn(Optional.of(favourite(id)));
        when(repository.findIds(CLIENT)).thenReturn(List.of(id));
        when(repository.delete(CLIENT, id)).thenReturn(true);

        service.list(CLIENT);
        service.get(CLIENT, id);
        service.rename(CLIENT, id, "Work");
        service.reorder(CLIENT, List.of(id.toString()));
        service.delete(CLIENT, id);

        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("Favourite#toString omits the client id and the label")
    void favouriteToStringIsRedacted() {
        Favourite f = new Favourite(UUID.randomUUID(), "cid-SECRET", "London", "LBL-SECRET", LONDON, 1,
                NOW_SECONDS, NOW_SECONDS);
        assertThat(f.toString()).doesNotContain("cid-SECRET").doesNotContain("LBL-SECRET");
    }
}
