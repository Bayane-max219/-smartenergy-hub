package com.smartenergy.storage.model;

/** Message Redis dont la structure est inutilisable (inutile de le rejouer). */
public class InvalidMeasurementException extends RuntimeException {

    public InvalidMeasurementException(String message) {
        super(message);
    }
}
