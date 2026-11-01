package com.openinternationalschedule;

/** The source could not be located, read, or understood well enough to publish. */
public class ScheduleParseException extends RuntimeException {

    public ScheduleParseException(String message) {
        super(message);
    }

    public ScheduleParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
