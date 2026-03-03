package com.semantyca.djinn.service.exceptions;

import lombok.Getter;

@Getter
public class RadioStationException extends RuntimeException {

    @Getter
    public enum ErrorType {
        STATION_NOT_ACTIVE("Radio station not broadcasting"),
        INITIALIZATION_FAILED("Failed to initialize radio station"),
        PLAYLIST_EMPTY("Playlist has no segments"),
        STATION_NOT_FOUND("radio station is not exists"),
        PLAYLIST_NOT_AVAILABLE("Playlist not available");

        private final String defaultMessage;

        ErrorType(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

    }

    private final ErrorType errorType;

    public RadioStationException(ErrorType errorType) {
        super(errorType.getDefaultMessage());
        this.errorType = errorType;
    }

    public RadioStationException(ErrorType errorType, String msg) {
        super(msg);
        this.errorType = errorType;
    }

    public String getDeveloperMessage() {
        return getMessage();
    }
}