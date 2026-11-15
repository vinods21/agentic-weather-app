package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.weather.provider.WeatherProvider;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.weather.Location;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/** Unit test (tester edge cases): mocked repository and provider, no transaction manager. No Spring, no Docker. */
class FavouriteServiceEdgeCaseTest {

    private static final String CLIENT = "client-edge";
    private static final Location NEW_YORK = new Location("New York", "US", 40.7128, -74.0060);
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private final FavouriteRepository repository = mock(FavouriteRepository.class);
    private final WeatherProvider provider = mock(WeatherProvider.class);
    private FavouriteService service;

    @BeforeEach
    void setUp() {
        service = new FavouriteService(repository, provider, TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC), new FavouritesProperties(3));
        when(repository.find(eq(CLIENT), any(UUID.class))).thenAnswer(inv -> Optional.of(
                new Favourite(inv.getArgument(1), CLIENT, "x", null, NEW_YORK, 1, NOW, NOW)));
    }

    @Test
    @DisplayName("AC-9, AC-11 (edge): the duplicate key is the normalised city; the provider and the row get the city as sent")
    void createUsesNormalisedKeyButStoresCityAsSent() {
        when(provider.locate("New   YORK")).thenReturn(NEW_YORK);

        service.create(CLIENT, "New   YORK", null);

        verify(repository, org.mockito.Mockito.times(2)).existsByKey(CLIENT, "new york");
        verify(provider).locate("New   YORK");
        ArgumentCaptor<Favourite> inserted = ArgumentCaptor.forClass(Favourite.class);
        verify(repository).insert(inserted.capture(), eq("new york"));
        assertThat(inserted.getValue().city()).isEqualTo("New   YORK");
        assertThat(inserted.getValue().label()).isNull();
    }

    @Test
    @DisplayName("AC-8 (edge): an unavailable provider takes no lock and inserts nothing")
    void providerFailureTouchesNoTransaction() {
        when(provider.locate(anyString())).thenThrow(new WeatherProviderUnavailableException("down", null));

        assertThatThrownBy(() -> service.create(CLIENT, "London", "Home"))
                .isInstanceOf(WeatherProviderUnavailableException.class);

        verify(repository, never()).lockClient(anyString());
        verify(repository, never()).insert(any(), anyString());
    }

    @Test
    @DisplayName("AC-13 (edge): with max-1 favourites a create proceeds; at max it is refused")
    void limitBoundary() {
        when(provider.locate(anyString())).thenReturn(NEW_YORK);
        when(repository.count(CLIENT)).thenReturn(2);

        service.create(CLIENT, "New York", null);
        verify(repository).insert(any(), eq("new york"));

        when(repository.count(CLIENT)).thenReturn(3);
        assertThatThrownBy(() -> service.create(CLIENT, "Paris", null))
                .isInstanceOf(FavouritesLimitReachedException.class);
    }

    @Test
    @DisplayName("AC-23 (edge): the same UUID in upper and lower case is a duplicate, found before the lock is taken")
    void reorderCaseVariantDuplicate() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.reorder(CLIENT, List.of(id.toString(), id.toString().toUpperCase())))
                .isInstanceOf(InvalidFavouriteOrderException.class)
                .hasMessage(InvalidFavouriteOrderException.DUPLICATES);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("AC-23 (edge): non-canonical UUID texts that UUID.fromString accepts are rejected without touching the repository")
    void reorderRejectsNonCanonicalUuidText() {
        for (String id : List.of("1-1-1-1-1", "0000000000000000-0000-0000-0000-000000000000",
                "{" + UUID.randomUUID() + "}", " " + UUID.randomUUID(), UUID.randomUUID().toString().replace("-", ""))) {
            assertThatThrownBy(() -> service.reorder(CLIENT, List.of(id)))
                    .as(id)
                    .isInstanceOf(InvalidFavouriteOrderException.class)
                    .hasMessage(InvalidFavouriteOrderException.NOT_UUIDS);
        }
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("AC-21 (edge): rename with null clears the label (null is passed to the repository)")
    void renameNullClears() {
        UUID id = UUID.randomUUID();
        when(repository.updateLabel(CLIENT, id, null, NOW)).thenReturn(Optional.of(
                new Favourite(id, CLIENT, "x", null, NEW_YORK, 1, NOW, NOW)));

        assertThat(service.rename(CLIENT, id, null).label()).isNull();
        verify(repository).updateLabel(CLIENT, id, null, NOW);
        verifyNoInteractions(provider);
    }
}
