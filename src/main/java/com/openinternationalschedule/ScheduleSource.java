package com.openinternationalschedule;

/**
 * Where the current schedule PDF lives.
 *
 * @param viewUrl       the link as published on the schedules page
 * @param downloadUrl   a URL that returns the PDF bytes directly
 * @param sourceUpdated the "last updated" text from the schedules page, or null
 */
public record ScheduleSource(String pageUrl, String viewUrl, String downloadUrl, String sourceUpdated) {
}
