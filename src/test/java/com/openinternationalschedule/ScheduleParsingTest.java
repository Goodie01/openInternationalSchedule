package com.openinternationalschedule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end parse of the 2026 weekend schedule PDF, checked against what the PDF visibly says. */
class ScheduleParsingTest {

    private static Schedule schedule;

    @BeforeAll
    static void parseFixture() throws IOException {
        try (InputStream in = ScheduleParsingTest.class.getResourceAsStream("/open-2026-weekend.pdf")) {
            schedule = Main.parse(in.readAllBytes(), new ScheduleSource(null, null, null, null));
        }
    }

    @Test
    void readsAllFiveDays() {
        assertEquals(
                List.of("2026-11-25", "2026-11-26", "2026-11-27", "2026-11-28", "2026-11-29"),
                schedule.days().stream().map(d -> d.date().toString()).toList());
        assertEquals(List.of(6, 13, 18, 19, 15), schedule.days().stream().map(d -> d.events().size()).toList());
    }

    @Test
    void timesAreInPacificStandardTime() {
        Event first = day("2026-11-25").events().getFirst();
        assertEquals("Onsite Registration Opens", first.title());
        assertEquals(OffsetDateTime.parse("2026-11-25T18:00-08:00"), first.start());
    }

    @Test
    void mergedTimeCellAppliesToEveryRowItSpans() {
        assertEquals(2, at("2026-11-26", "2026-11-26T12:30-08:00").size(), "Thu 12:30 PM prelims");
        assertEquals(List.of("Dinner Break", "JUNIORS MEET & GREET (Age 19 & under)"),
                at("2026-11-28", "2026-11-28T17:00-08:00").stream()
                        .filter(e -> e.sourceTime().equals("5:00 PM")).map(Event::title).toList());
        List<Event> sunday330 = at("2026-11-29", "2026-11-29T15:30-08:00");
        assertEquals(List.of("WCS J&J PRELIMS & FINALS / AWARDS:", "Champion MVP Award Presentation"),
                sunday330.stream().map(Event::title).toList());
    }

    @Test
    void rangesCrossingMidnightEndTheNextDay() {
        Event lateNight = at("2026-11-25", "2026-11-25T21:00-08:00").getFirst();
        assertEquals(OffsetDateTime.parse("2026-11-26T03:00-08:00"), lateNight.end());
    }

    @Test
    void afterMidnightRowsStayInTheirPrintedBlockButMoveToTheNextDate() {
        Event saturdayLate = day("2026-11-28").events().getLast();
        assertEquals("12:30 – 5:00 AM", saturdayLate.sourceTime());
        assertEquals(OffsetDateTime.parse("2026-11-29T00:30-08:00"), saturdayLate.start());
        assertEquals(OffsetDateTime.parse("2026-11-29T05:00-08:00"), saturdayLate.end());
        assertEquals("2026-11-29T08:30:00Z", saturdayLate.start().toInstant().toString());
    }

    @Test
    void lastRowOfRightColumnIsRead() {
        Event party = day("2026-11-29").events().getLast();
        assertTrue(party.title().startsWith("Time to Party!"), party.title());
        assertEquals(OffsetDateTime.parse("2026-11-30T05:00-08:00"), party.end());
    }

    @Test
    void titleAndDetailComeFromSeparateLines() {
        Event e = at("2026-11-27", "2026-11-27T20:00-08:00").getFirst();
        assertEquals("ROUTINE FINALS:", e.title());
        assertEquals("Rising Star (Classic)", e.detail());
    }

    @Test
    void textAboveFirstDayBecomesANote() {
        assertEquals(1, schedule.notes().size());
        assertTrue(schedule.notes().getFirst().startsWith("*COMPETITORS CHECK IN AT GREEN ROOM"));
    }

    private static Day day(String date) {
        return schedule.days().stream().filter(d -> d.date().equals(LocalDate.parse(date))).findFirst().orElseThrow();
    }

    private static List<Event> at(String date, String start) {
        OffsetDateTime t = OffsetDateTime.parse(start);
        return day(date).events().stream().filter(e -> e.start().isEqual(t)).toList();
    }
}
