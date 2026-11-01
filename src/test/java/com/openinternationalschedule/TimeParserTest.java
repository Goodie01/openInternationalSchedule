package com.openinternationalschedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "6:30 PM            | 18:30 |",
            "12:00 PM           | 12:00 |",
            "12:30 AM           | 00:30 |",
            "6:00 – 10:30 AM    | 06:00 | 10:30",
            "4:00 - 6:30 PM     | 16:00 | 18:30",
            "9:00 PM – 3:00 AM  | 21:00 | 03:00",
            "10:30 PM – 12:30 AM| 22:30 | 00:30",
            "12:30 – 5:00 AM    | 00:30 | 05:00",
            "11:00 – 1:00 PM    | 11:00 | 13:00",
            "9:30 - 11:30 AM    | 09:30 | 11:30",
            "7 PM               | 19:00 |",
            "Noon – 2:00 PM     | 12:00 | 14:00",
    })
    void parsesFormatsSeenInTheSchedule(String text, LocalTime start, LocalTime end) {
        TimeParser.TimeRange range = TimeParser.parse(text);
        assertEquals(start, range.start());
        assertEquals(end, range.end());
    }

    @Test
    void singleTimeHasNoEnd() {
        assertNull(TimeParser.parse("8:00 PM").end());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIME", "6:30", "6:30 – 7:00", "13:00 PM", "tbd", ""})
    void rejectsUnparseableTimes(String text) {
        assertThrows(ScheduleParseException.class, () -> TimeParser.parse(text));
    }
}
