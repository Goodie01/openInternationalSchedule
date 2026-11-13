package com.openinternationalschedule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns the PDF's raw table rows into dated, zoned events. */
public final class ScheduleBuilder {

    public static final ZoneId SOURCE_ZONE = ZoneId.of("America/Los_Angeles");

    /** Parsing the weekday as well makes java.time reject a header whose weekday doesn't match its date. */
    private static final DateTimeFormatter DAY_HEADER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("EEEE, MMMM d, uuuu").toFormatter(Locale.US);
    /** Headers like "WEDNESDAY, NOVEMBER 25" leave the year to be worked out from the weekday. */
    private static final DateTimeFormatter DAY_HEADER_NO_YEAR = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("EEEE, MMMM d").toFormatter(Locale.US);
    /** An end time printed in the event text rather than the time column, e.g. "Late Night Dancing (Until 3:00 AM)". */
    private static final Pattern UNTIL = Pattern.compile("\\s*\\(until\\s+([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGISTRATION =
            Pattern.compile("^(.*?REGISTRATION.*?)\\s*:?\\s*OPENS\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    /**
     * A row whose start is this much earlier than the row above it is read as having crossed midnight, e.g.
     * {@code 12:30 – 5:00 AM} after {@code 10:30 PM}. Smaller backward steps are treated as listing quirks.
     */
    private static final Duration ROLLOVER_GAP = Duration.ofHours(6);

    public record Result(List<String> notes, List<Day> days) {
    }

    private ScheduleBuilder() {
    }

    public static Result build(List<RawRow> rows) {
        return build(rows, LocalDate.now(SOURCE_ZONE));
    }

    /**
     * @param today used to pick the year for day headers that don't print one: the year within one of
     *              {@code today}'s whose calendar puts that date on the printed weekday
     */
    public static Result build(List<RawRow> rows, LocalDate today) {
        List<String> notes = new ArrayList<>();
        List<Day> days = new ArrayList<>();
        DayBuilder current = null;

        for (RawRow row : rows) {
            if (row.event().isEmpty() && PdfTableParser.DAY_HEADER.matcher(row.fullText()).matches()) {
                if (current != null) {
                    days.add(current.build());
                }
                current = new DayBuilder(parseDate(row.fullText(), today), row.fullText());
            } else if (current == null) {
                notes.add(row.fullText());
            } else if (row.time().equalsIgnoreCase("TIME")) {
                current.addColumnHeader(row.event());
            } else if (row.time().isBlank()) {
                current.notes.add(row.fullText());
            } else {
                current.addEvent(row.time(), row.event());
            }
        }
        if (current != null) {
            // Untimed text after the last day's final event is a page footer, which applies to the whole schedule.
            notes.addAll(current.takeTrailingNotes());
            days.add(current.build());
        }
        return new Result(List.copyOf(notes), List.copyOf(days));
    }

    private static LocalDate parseDate(String header, LocalDate today) {
        String text = header.replaceAll("\\s+", " ");
        try {
            return LocalDate.parse(text, DAY_HEADER);
        } catch (DateTimeParseException withYear) {
            TemporalAccessor parsed;
            try {
                parsed = DAY_HEADER_NO_YEAR.parse(text);
            } catch (DateTimeParseException e) {
                throw new ScheduleParseException("Bad day header: \"" + header + "\"", e);
            }
            MonthDay monthDay = MonthDay.from(parsed);
            DayOfWeek weekday = DayOfWeek.from(parsed);
            // Weekdays shift by one or two each year, so at most one year in this window can match.
            for (int year = today.getYear() - 1; year <= today.getYear() + 1; year++) {
                if (monthDay.isValidYear(year) && monthDay.atYear(year).getDayOfWeek() == weekday) {
                    return monthDay.atYear(year);
                }
            }
            throw new ScheduleParseException("Day header \"" + header + "\" matches no year near " + today.getYear());
        }
    }

    /** An "(Until 3:00 AM)" found in some text, and that text without it. */
    private record Until(LocalTime time, String remainder) {
        static Until find(String text) {
            Matcher m = UNTIL.matcher(text);
            while (m.find()) {
                try {
                    TimeParser.TimeRange range = TimeParser.parse(m.group(1));
                    if (range.end() == null) {
                        String remainder = (text.substring(0, m.start()) + text.substring(m.end())).strip();
                        return new Until(range.start(), remainder);
                    }
                } catch (ScheduleParseException e) {
                    // "(Until further notice)" and the like aren't end times; keep looking.
                }
            }
            return null;
        }
    }

    private static final class DayBuilder {
        private final LocalDate date;
        private final String label;
        private final List<Event> events = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private LocalDateTime previousStart;
        private int dayOffset;
        private int notesBeforeLastEvent;

        DayBuilder(LocalDate date, String label) {
            this.date = date;
            this.label = label;
        }

        /**
         * The "TIME" row's right-hand cell usually announces when onsite registration opens. That becomes an event
         * of its own; it doesn't take part in rollover detection because it is printed out of time order.
         */
        void addColumnHeader(String text) {
            Matcher m = REGISTRATION.matcher(text.replace('\n', ' '));
            if (!m.matches()) {
                if (!text.isBlank()) {
                    notes.add(text);
                }
                return;
            }
            TimeParser.TimeRange range = TimeParser.parse(m.group(2));
            String title = capitalise(m.group(1).strip()) + " Opens";
            events.add(new Event(zoned(date.atTime(range.start())), null, title, null, m.group(2)));
        }

        void addEvent(String timeText, String eventText) {
            TimeParser.TimeRange range = TimeParser.parse(timeText);
            LocalDateTime start = date.plusDays(dayOffset).atTime(range.start());
            if (previousStart != null && start.isBefore(previousStart.minus(ROLLOVER_GAP))) {
                dayOffset++;
                start = start.plusDays(1);
            }
            previousStart = start;

            String[] lines = eventText.split("\n", 2);
            String title = lines[0].strip();
            String detail = lines.length > 1 ? lines[1].replace('\n', ' ').strip() : null;

            LocalTime endTime = range.end();
            if (endTime == null) {
                // The end is printed in the text instead; drop it there, since it is in LA time and the site shows
                // the end converted to the visitor's zone.
                Until until = Until.find(title);
                if (until != null) {
                    title = until.remainder();
                } else if (detail != null && (until = Until.find(detail)) != null) {
                    detail = until.remainder().isEmpty() ? null : until.remainder();
                }
                endTime = until == null ? null : until.time();
            }
            LocalDateTime end = null;
            if (endTime != null) {
                end = start.toLocalDate().atTime(endTime);
                if (!end.isAfter(start)) {
                    end = end.plusDays(1);
                }
            }

            if (title.isEmpty()) {
                throw new ScheduleParseException("Empty event at " + timeText + " on " + label);
            }
            events.add(new Event(zoned(start), end == null ? null : zoned(end), title, detail, timeText));
            notesBeforeLastEvent = notes.size();
        }

        /** Removes and returns the untimed rows that came after this day's last event. */
        List<String> takeTrailingNotes() {
            List<String> trailing = notes.subList(notesBeforeLastEvent, notes.size());
            List<String> taken = List.copyOf(trailing);
            trailing.clear();
            return taken;
        }

        Day build() {
            List<Event> sorted = new ArrayList<>(events);
            sorted.sort(Comparator.comparing(Event::start));
            return new Day(date, label, List.copyOf(sorted), List.copyOf(notes));
        }

        private static OffsetDateTime zoned(LocalDateTime local) {
            return local.atZone(SOURCE_ZONE).toOffsetDateTime();
        }

        /** "ONSITE REGISTRATION" → "Onsite Registration". */
        private static String capitalise(String text) {
            if (!text.equals(text.toUpperCase(Locale.ROOT))) {
                return text;
            }
            StringBuilder sb = new StringBuilder();
            for (String word : text.toLowerCase(Locale.ROOT).split(" ")) {
                if (!word.isEmpty()) {
                    sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
                }
            }
            return sb.toString().strip();
        }
    }
}
