package com.openinternationalschedule;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Sanity checks run before publishing. A failure here means the PDF layout probably changed; the job fails, the last
 * good site stays live, and CI opens an issue.
 */
public final class Validator {

    static final int MIN_DAYS = 3;
    static final int MIN_EVENTS_PER_DAY = 3;
    /** Late-night items printed under a day may run into the next morning, but not beyond. */
    private static final Duration MAX_SPILL = Duration.ofHours(36);

    private Validator() {
    }

    public static void validate(ScheduleBuilder.Result result) {
        List<String> problems = new ArrayList<>();
        List<Day> days = result.days();

        if (days.size() < MIN_DAYS) {
            problems.add("Only " + days.size() + " day(s) found; expected at least " + MIN_DAYS);
        }
        for (int i = 0; i < days.size(); i++) {
            Day day = days.get(i);
            if (i > 0 && ChronoUnit.DAYS.between(days.get(i - 1).date(), day.date()) != 1) {
                problems.add(day.label() + " does not directly follow " + days.get(i - 1).label());
            }
            if (day.events().size() < MIN_EVENTS_PER_DAY) {
                problems.add(day.label() + " has only " + day.events().size() + " event(s)");
            }
            var dayStart = day.date().atStartOfDay(ScheduleBuilder.SOURCE_ZONE).toOffsetDateTime();
            for (Event event : day.events()) {
                if (event.start().isBefore(dayStart) || event.start().isAfter(dayStart.plus(MAX_SPILL))) {
                    problems.add(day.label() + ": \"" + event.title() + "\" starts at " + event.start());
                }
                if (event.end() != null && !event.end().isAfter(event.start())) {
                    problems.add(day.label() + ": \"" + event.title() + "\" ends before it starts");
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new ScheduleParseException("Schedule failed validation:\n - " + String.join("\n - ", problems));
        }
    }
}
