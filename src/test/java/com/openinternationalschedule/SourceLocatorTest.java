package com.openinternationalschedule;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceLocatorTest {

    private static final String PAGE = "https://theopenswing.com/schedules/";

    @Test
    void picksTheNewestWeekendScheduleAndRewritesDriveLinks() {
        var html = """
                <a href="https://drive.google.com/file/d/OLD_id/view">2025 WEEKEND SCHEDULE (PDF)</a>
                <a href="https://drive.google.com/file/d/17OJ0J_9e-AYW/view?usp=drive_link">2026 WEEKEND SCHEDULE (PDF)</a>
                <a href="/wp-content/uploads/2024/11/Open-Workshop-Schedule.pdf">DOWNLOAD 2024 WORKSHOP SCHEDULE (PDF)</a>
                <p>Weekend Schedule last updated: August 26, 2026.</p>
                """;
        ScheduleSource source = SourceLocator.locate(Jsoup.parse(html, PAGE), PAGE);
        assertEquals("https://drive.google.com/uc?export=download&id=17OJ0J_9e-AYW", source.downloadUrl());
        assertEquals("August 26, 2026", source.sourceUpdated());
    }

    @Test
    void acceptsADirectPdfLink() {
        var html = "<a href=\"/wp-content/uploads/2027/Weekend.pdf\">2027 Weekend Schedule (PDF)</a>";
        ScheduleSource source = SourceLocator.locate(Jsoup.parse(html, PAGE), PAGE);
        assertEquals("https://theopenswing.com/wp-content/uploads/2027/Weekend.pdf", source.downloadUrl());
    }

    @Test
    void failsLoudlyWhenTheLinkIsGone() {
        assertThrows(ScheduleParseException.class,
                () -> SourceLocator.locate(Jsoup.parse("<a href='/x'>Schedule</a>", PAGE), PAGE));
    }
}
