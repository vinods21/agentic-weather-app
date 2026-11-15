package com.example.weather.favourites;

/** No favourite with this id exists for this client (404 NOT_FOUND); another client's id counts as missing. */
public class FavouriteNotFoundException extends RuntimeException {

    public FavouriteNotFoundException() {
        super("No favourite exists with this id.", null, false, false);
    }
}
