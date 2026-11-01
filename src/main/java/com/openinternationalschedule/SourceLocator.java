package com.openinternationalschedule;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the current weekend-schedule PDF linked from the event's schedules page. */
public final class SourceLocator {

    public static final String DEFAULT_PAGE = "https://theopenswing.com/schedules/";
    static final String USER_AGENT =
            "Mozilla/5.0 (compatible; open-international-schedule; +https://github.com/) Java-HttpClient";

    private static final Pattern LINK_TEXT = Pattern.compile("WEEKEND\\s+SCHEDULE.*PDF", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern DRIVE_ID = Pattern.compile("drive\\.google\\.com/(?:file/d/|open\\?id=|uc\\?.*id=)([\\w-]+)");
    private static final Pattern UPDATED =
            Pattern.compile("Weekend Schedule last updated:\\s*(\\w+ \\d{1,2}, \\d{4})", Pattern.CASE_INSENSITIVE);

    private SourceLocator() {
    }

    public static ScheduleSource locate(String pageUrl) throws IOException {
        Document page = Jsoup.connect(pageUrl).userAgent(USER_AGENT).timeout(30_000).get();
        return locate(page, pageUrl);
    }

    static ScheduleSource locate(Document page, String pageUrl) {
        Element link = page.select("a[href]").stream()
                .filter(a -> LINK_TEXT.matcher(a.text()).find())
                .filter(a -> toDownloadUrl(a.absUrl("href")).isPresent())
                .max(Comparator.comparingInt(SourceLocator::yearOf))
                .orElseThrow(() -> new ScheduleParseException(
                        "No \"WEEKEND SCHEDULE (PDF)\" link to a Drive file or PDF found on " + pageUrl));

        String viewUrl = link.absUrl("href");
        Matcher updated = UPDATED.matcher(page.body().wholeText());
        return new ScheduleSource(pageUrl, viewUrl, toDownloadUrl(viewUrl).orElseThrow(),
                updated.find() ? updated.group(1).strip() : null);
    }

    /** Google Drive viewer links are rewritten to the direct-download endpoint; plain PDF links are used as-is. */
    static Optional<String> toDownloadUrl(String href) {
        Matcher drive = DRIVE_ID.matcher(href);
        if (drive.find()) {
            return Optional.of("https://drive.google.com/uc?export=download&id=" + drive.group(1));
        }
        if (href.toLowerCase().replaceAll("[?#].*$", "").endsWith(".pdf")) {
            return Optional.of(href);
        }
        return Optional.empty();
    }

    private static int yearOf(Element link) {
        Matcher m = YEAR.matcher(link.text());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
