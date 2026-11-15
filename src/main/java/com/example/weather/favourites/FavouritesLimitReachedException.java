package com.example.weather.favourites;

/** The client already has the configured maximum of favourites (409 FAVOURITES_LIMIT_REACHED). */
public class FavouritesLimitReachedException extends RuntimeException {

    private final int max;

    public FavouritesLimitReachedException(int max) {
        super("This client already has the maximum of " + max + " favourites.", null, false, false);
        this.max = max;
    }

    public int max() {
        return max;
    }
}
