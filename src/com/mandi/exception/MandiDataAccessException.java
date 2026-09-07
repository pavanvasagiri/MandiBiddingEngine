package com.mandi.exception;

/** Wraps low-level {@link java.sql.SQLException}s behind a domain-checked exception. */
public class MandiDataAccessException extends RuntimeException {

    public MandiDataAccessException(String message) {
        super(message);
    }

    public MandiDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
