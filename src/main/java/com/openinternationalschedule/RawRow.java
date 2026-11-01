package com.openinternationalschedule;

/**
 * One row of a schedule table as read from the PDF, in reading order.
 *
 * @param time     text of the time cell (shared by every row a merged time cell spans); blank for header/note rows
 * @param event    text of the event cell, lines separated by {@code \n}
 * @param fullText text across the whole row width, used for full-width day headers and notes
 */
public record RawRow(String time, String event, String fullText) {
}
