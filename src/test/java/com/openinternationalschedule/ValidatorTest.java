package com.openinternationalschedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorTest {

    @Test
    void rejectsAnEmptyParse() {
        ScheduleParseException e = assertThrows(ScheduleParseException.class,
                () -> Validator.validate(new ScheduleBuilder.Result(List.of(), List.of())));
        assertTrue(e.getMessage().contains("0 day(s)"));
    }

    @Test
    void rejectsGapsBetweenDays() {
        List<RawRow> rows = List.of(
                header("Wednesday, November 25, 2026"), row("1:00 PM"), row("2:00 PM"), row("3:00 PM"),
                header("Friday, November 27, 2026"), row("1:00 PM"), row("2:00 PM"), row("3:00 PM"),
                header("Saturday, November 28, 2026"), row("1:00 PM"), row("2:00 PM"), row("3:00 PM"));
        ScheduleParseException e = assertThrows(ScheduleParseException.class,
                () -> Validator.validate(ScheduleBuilder.build(rows)));
        assertTrue(e.getMessage().contains("does not directly follow"), e.getMessage());
    }

    @Test
    void builderRejectsAWeekdayThatDoesNotMatchTheDate() {
        assertThrows(ScheduleParseException.class,
                () -> ScheduleBuilder.build(List.of(header("Tuesday, November 25, 2026"))));
    }

    @Test
    void builderPicksTheYearWhoseCalendarMatchesAYearlessHeader() {
        List<RawRow> rows = List.of(header("WEDNESDAY, NOVEMBER 25"), row("1:00 PM"));
        // Nov 25 is a Wednesday in 2026 but not in 2025 or 2027, so a run in January 2027 still lands on 2026.
        assertEquals(LocalDate.parse("2026-11-25"),
                ScheduleBuilder.build(rows, LocalDate.parse("2027-01-10")).days().getFirst().date());
    }

    @Test
    void builderRejectsAYearlessHeaderNoNearbyYearMatches() {
        List<RawRow> rows = List.of(header("MONDAY, NOVEMBER 25"));
        assertThrows(ScheduleParseException.class, () -> ScheduleBuilder.build(rows, LocalDate.parse("2026-11-14")));
    }

    private static RawRow header(String text) {
        return new RawRow("", "", text);
    }

    private static RawRow row(String time) {
        return new RawRow(time, "Something", time + " Something");
    }
}
