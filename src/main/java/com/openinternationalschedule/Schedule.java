package com.openinternationalschedule;

import java.time.Instant;
import java.util.List;

/**
 * The document written to {@code schedule.json} and read by the static site.
 *
 * @param sourceUpdated the "last updated" text from the schedules page, if present
 * @param notes         rows above the first day block (e.g. check-in instructions)
 */
public record Schedule(
        Instant generatedAt,
        String sourceTimeZone,
        String sourcePageUrl,
        String pdfUrl,
        String pdfSha256,
        String sourceUpdated,
        List<String> notes,
        List<Day> days) {
}
