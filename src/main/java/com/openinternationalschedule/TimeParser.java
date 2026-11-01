package com.openinternationalschedule;

import java.time.LocalTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the time cells printed in the PDF, e.g. {@code 6:30 PM}, {@code 6:00 – 11:00 AM}, {@code 9:00 PM – 3:00 AM}. */
public final class TimeParser {

    /** @param end null for a single time; may be earlier than {@code start} when the range crosses midnight */
    public record TimeRange(LocalTime start, LocalTime end) {
    }

    private static final String CLOCK = "(\\d{1,2})(?::(\\d{2}))?\\s*(AM|PM|A\\.M\\.|P\\.M\\.)?";
    private static final Pattern RANGE =
            Pattern.compile("^" + CLOCK + "(?:\\s*(?:-|–|—|to)\\s*" + CLOCK + ")?$", Pattern.CASE_INSENSITIVE);

    private TimeParser() {
    }

    public static TimeRange parse(String text) {
        String normalised = text.replace(' ', ' ').replaceAll("\\s+", " ").strip()
                .replaceAll("(?i)\\bnoon\\b", "12:00 PM")
                .replaceAll("(?i)\\bmidnight\\b", "12:00 AM");
        Matcher m = RANGE.matcher(normalised);
        if (!m.matches()) {
            throw new ScheduleParseException("Unrecognised time: \"" + text + "\"");
        }
        String startMeridiem = m.group(3);
        boolean isRange = m.group(4) != null;
        String endMeridiem = m.group(6);

        if (!isRange) {
            if (startMeridiem == null) {
                throw new ScheduleParseException("Time without AM/PM: \"" + text + "\"");
            }
            return new TimeRange(toTime(m.group(1), m.group(2), startMeridiem, text), null);
        }
        if (endMeridiem == null) {
            throw new ScheduleParseException("Time range without AM/PM on its end: \"" + text + "\"");
        }
        LocalTime end = toTime(m.group(4), m.group(5), endMeridiem, text);
        LocalTime start;
        if (startMeridiem != null) {
            start = toTime(m.group(1), m.group(2), startMeridiem, text);
        } else {
            // "6:00 – 10:30 AM" shares the end's meridiem, unless that would put the start after the end, as in
            // "11:00 – 1:00 PM".
            start = toTime(m.group(1), m.group(2), endMeridiem, text);
            if (start.isAfter(end)) {
                start = start.plusHours(12);
            }
        }
        return new TimeRange(start, end);
    }

    private static LocalTime toTime(String hours, String minutes, String meridiem, String original) {
        int h = Integer.parseInt(hours);
        int min = minutes == null ? 0 : Integer.parseInt(minutes);
        if (h < 1 || h > 12 || min > 59) {
            throw new ScheduleParseException("Impossible time: \"" + original + "\"");
        }
        boolean pm = meridiem.toUpperCase(Locale.ROOT).startsWith("P");
        return LocalTime.of(h % 12 + (pm ? 12 : 0), min);
    }
}
