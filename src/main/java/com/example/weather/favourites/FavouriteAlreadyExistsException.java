package com.example.weather.favourites;

/** The client already has a favourite with the same normalised city (409 FAVOURITE_ALREADY_EXISTS). */
public class FavouriteAlreadyExistsException extends RuntimeException {

    public FavouriteAlreadyExistsException() {
        super("A favourite for this city already exists.", null, false, false);
    }
}
