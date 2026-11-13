package com.openinternationalschedule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end parse of the November 2026 "full schedule" PDF: one long single-column table, day headers without a
 * year, cells drawn as filled rectangles, and end times printed as "(Until …)" in the event text. Checked against
 * what the PDF visibly says.
 */
class FullScheduleParsingTest {

    private static Schedule schedule;

    @BeforeAll
    static void parseFixture() throws IOException {
        try (InputStream in = FullScheduleParsingTest.class.getResourceAsStream("/open-2026-full-schedule.pdf")) {
            schedule = Main.parse(in.readAllBytes(), new ScheduleSource(null, null, null, null),
                    LocalDate.parse("2026-11-14"));
        }
    }

    @Test
    void readsAllFiveDaysWithTheYearWorkedOutFromTheWeekday() {
        assertEquals(
                List.of("2026-11-25", "2026-11-26", "2026-11-27", "2026-11-28", "2026-11-29"),
                schedule.days().stream().map(d -> d.date().toString()).toList());
        assertEquals(List.of(5, 14, 19, 23, 17), schedule.days().stream().map(d -> d.events().size()).toList());
        assertEquals("WEDNESDAY, NOVEMBER 25", schedule.days().getFirst().label());
    }

    @Test
    void firstEventIsInPacificStandardTime() {
        Event first = day("2026-11-25").events().getFirst();
        assertEquals("Onsite Registration Opens", first.title());
        assertEquals(OffsetDateTime.parse("2026-11-25T18:00-08:00"), first.start());
    }

    @Test
    void mergedTimeCellAppliesToEveryRowItSpans() {
        assertEquals(List.of(
                        "Competition Deadline: WCS Strictly Champions",
                        "Competitors' Meeting & Draw for Dance Order*",
                        "Jordan Frisbee & Tatiana Mollmann · WCS Workshop"),
                titlesAt("2026-11-27", "2026-11-27T11:00-08:00"));
    }

    @Test
    void untilInTheTextBecomesTheEndTime() {
        Event lateNight = at("2026-11-25", "2026-11-25T21:00-08:00").getFirst();
        assertEquals("Late Night Dancing (Academy Ballroom)", lateNight.title());
        assertEquals(OffsetDateTime.parse("2026-11-26T03:00-08:00"), lateNight.end());

        Event presale = at("2026-11-29", "2026-11-29T10:00-08:00").get(1);
        assertEquals("PRESALE Reserved Seating for 2027 Tickets", presale.title());
        assertEquals("(Onsite Registration)", presale.detail());
        assertEquals(OffsetDateTime.parse("2026-11-29T11:30-08:00"), presale.end());

        assertNull(at("2026-11-26", "2026-11-26T16:00-08:00").getFirst().end(), "no (Until …) printed");
    }

    @Test
    void afterMidnightRowsStayInTheirPrintedBlockButMoveToTheNextDate() {
        Event fridayLate = day("2026-11-27").events().getLast();
        assertEquals("1:30 AM", fridayLate.sourceTime());
        assertEquals(OffsetDateTime.parse("2026-11-28T01:30-08:00"), fridayLate.start());
        assertEquals(OffsetDateTime.parse("2026-11-28T05:00-08:00"), fridayLate.end());
    }

    @Test
    void lastRowBeforeTheFooterIsRead() {
        Event party = day("2026-11-29").events().getLast();
        assertEquals("Time to Party!", party.title());
        assertEquals(OffsetDateTime.parse("2026-11-30T05:00-08:00"), party.end());
    }

    @Test
    void mixedFontRunsAndBadgesKeepReadingOrder() {
        assertEquals(List.of("SHAG STRICTLY FINALS / AWARDS: Juniors"), titlesAt("2026-11-27", "2026-11-27T14:30-08:00"));
        Event workshop = at("2026-11-25", "2026-11-25T19:00-08:00").getFirst();
        assertEquals("Zac & Maddy Skinner FREE", workshop.title());
        assertEquals("Novice/Intermediate West Coast Swing", workshop.detail());
    }

    @Test
    void footnoteBelowTheLastTableBecomesAScheduleNoteAndTheLegendIsDropped() {
        assertEquals(List.of("*Competitors' Meeting: Routine Competitors, Judges, Sponsors, MC's and DJ's welcomed. "
                        + "Competitors check in at the Green Room per the Competitor Check-In Schedule."),
                schedule.notes());
    }

    private static Day day(String date) {
        return schedule.days().stream().filter(d -> d.date().equals(LocalDate.parse(date))).findFirst().orElseThrow();
    }

    private static List<Event> at(String date, String start) {
        OffsetDateTime t = OffsetDateTime.parse(start);
        return day(date).events().stream().filter(e -> e.start().isEqual(t)).toList();
    }

    private static List<String> titlesAt(String date, String start) {
        return at(date, start).stream().map(Event::title).toList();
    }
}
