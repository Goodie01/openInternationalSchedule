package com.openinternationalschedule;

import java.time.LocalDate;
import java.util.List;

/**
 * One day block of the PDF. Events after midnight stay in the block they were printed in, but their timestamps fall on
 * the following calendar date.
 *
 * @param notes untimed rows inside the block
 */
public record Day(LocalDate date, String label, List<Event> events, List<String> notes) {
}
