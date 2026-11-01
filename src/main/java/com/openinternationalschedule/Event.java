package com.openinternationalschedule;

import java.time.OffsetDateTime;

/**
 * A scheduled item. Times carry the source (Los Angeles) offset so the browser can convert them to any zone.
 *
 * @param end        null for items with only a start time
 * @param detail     null when the PDF cell had a single line
 * @param sourceTime the time text exactly as printed in the PDF
 */
public record Event(OffsetDateTime start, OffsetDateTime end, String title, String detail, String sourceTime) {
}
