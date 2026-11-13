package com.openinternationalschedule;

import org.apache.pdfbox.pdmodel.PDDocument;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.Rectangle;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;

import com.openinternationalschedule.RulingExtractor.Segment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the two-column (time | event) schedule tables out of the PDF. A page may hold several tables side by side
 * (older layouts) or one table running down the page (the 2026 "full schedule" layout).
 * <p>
 * Tabula's own table extraction loses the text of time cells that are merged across several event rows, so the grid
 * is rebuilt here from the visible border lines ({@link RulingExtractor}): the event column's horizontal lines give
 * one band per event, the time column's lines give (possibly taller) bands per time slot, and each event band takes
 * the time of the slot that contains it. Tabula is only used for text-in-rectangle lookups.
 */
public final class PdfTableParser {

    /** "Wednesday, November 25, 2026" or "WEDNESDAY, NOVEMBER 25"; the year is optional. */
    static final Pattern DAY_HEADER = Pattern.compile(
            "^(Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day,\\s+\\w+\\s+\\d{1,2}(,\\s+\\d{4})?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_HEADER = Pattern.compile("^TIME\\s+(.+)$");

    /** Rulings closer than this (points) are treated as the same line. */
    private static final float SNAP = 1.5f;
    /** Bands shorter than this are slivers between double lines, not rows. */
    private static final float MIN_BAND = 4f;
    /** Slack when collecting text inside a cell, since glyph boxes can touch the border. */
    private static final float PAD = 1f;
    /**
     * Horizontal rulings shorter than this (or than the time column, if narrower) are glyph outlines or legend boxes,
     * not table borders.
     */
    private static final float MIN_TABLE_RULING = 40f;

    private PdfTableParser() {
    }

    public static List<RawRow> parse(PDDocument doc) throws IOException {
        List<RawRow> rows = new ArrayList<>();
        try (ObjectExtractor extractor = new ObjectExtractor(doc)) {
            for (int pageNo = 1; pageNo <= doc.getNumberOfPages(); pageNo++) {
                Page page = extractor.extract(pageNo);
                RulingExtractor.Lines lines = RulingExtractor.extract(doc.getPage(pageNo - 1));
                for (float[] span : tableSpans(lines)) {
                    rows.addAll(parseColumn(page, lines, span[0] - SNAP, span[1] + SNAP));
                }
            }
        }
        return rows;
    }

    /**
     * The x-extents of the tables on a page, left to right: the long horizontal rulings, merged where they overlap
     * or touch (a time cell's edge ends where its event cell's begins).
     */
    private static List<float[]> tableSpans(RulingExtractor.Lines lines) {
        List<Segment> sorted = lines.horizontals().stream()
                .filter(r -> r.length() >= MIN_TABLE_RULING)
                .sorted(Comparator.comparingDouble(Segment::start))
                .toList();
        List<float[]> spans = new ArrayList<>();
        for (Segment r : sorted) {
            if (!spans.isEmpty() && r.start() <= spans.getLast()[1] + SNAP) {
                spans.getLast()[1] = Math.max(spans.getLast()[1], r.end());
            } else {
                spans.add(new float[]{r.start(), r.end()});
            }
        }
        return spans;
    }

    /** Parses the table whose vertical rulings fall within {@code [minX, maxX]}. */
    private static List<RawRow> parseColumn(Page page, RulingExtractor.Lines lines, float minX, float maxX) {
        List<Segment> verticals = lines.verticals().stream()
                .filter(r -> r.pos() >= minX && r.pos() <= maxX && r.length() > MIN_BAND)
                .toList();
        if (verticals.isEmpty()) {
            return List.of();
        }

        float left = (float) verticals.stream().mapToDouble(Segment::pos).min().orElseThrow();
        float right = (float) verticals.stream().mapToDouble(Segment::pos).max().orElseThrow();
        Float divider = findDivider(verticals, left, right);
        if (divider == null) {
            throw new ScheduleParseException(
                    "No time/event column divider found in table spanning x=%.0f..%.0f".formatted(left, right));
        }

        List<Segment> horizontals = lines.horizontals().stream()
                .filter(r -> r.end() > left + SNAP && r.start() < right - SNAP)
                .filter(r -> r.length() >= Math.min(MIN_TABLE_RULING, divider - left))
                .toList();
        float pageBottom = (float) page.getHeight();
        List<float[]> eventBands = bandsAt(horizontals, (divider + right) / 2, pageBottom);
        List<float[]> timeBands = bandsAt(horizontals, (left + divider) / 2, pageBottom);

        List<RawRow> rows = new ArrayList<>();
        for (float[] band : eventBands) {
            String full = text(page, band[0], left, right, band[1]);
            if (full.isEmpty()) {
                continue;
            }
            if (full.lines().anyMatch(line -> DAY_HEADER.matcher(line).matches())) {
                // A day header, possibly sharing an unruled gap between tables with the column header row.
                full.lines().map(PdfTableParser::unruledRow).forEach(rows::add);
                continue;
            }
            if (band[1] >= pageBottom) {
                footnotes(full).ifPresent(note -> rows.add(new RawRow("", "", note)));
                continue;
            }
            float centre = (band[0] + band[1]) / 2;
            String time = timeBands.stream()
                    .filter(t -> t[0] <= centre && centre <= t[1])
                    .findFirst()
                    .map(t -> text(page, t[0], left, divider, t[1]).replace('\n', ' '))
                    .orElse("");
            String event = text(page, band[0], divider, right, band[1]);
            rows.add(new RawRow(time, event, full));
        }
        return rows;
    }

    /**
     * The page footer below the last table. In the 2026 layout it holds a colour legend (meaningless without the
     * colours) and then {@code *}-marked footnotes; keep the footnotes if there are any, else the whole footer.
     */
    private static Optional<String> footnotes(String footer) {
        List<String> lines = footer.lines().toList();
        int first = 0;
        while (first < lines.size() && !lines.get(first).startsWith("*")) {
            first++;
        }
        List<String> kept = first < lines.size() ? lines.subList(first, lines.size()) : lines;
        return kept.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", kept));
    }

    /** One line of text from outside the ruled grid, e.g. a day header or a "TIME | EVENT" column header. */
    private static RawRow unruledRow(String line) {
        Matcher m = COLUMN_HEADER.matcher(line);
        if (m.matches()) {
            return new RawRow("TIME", m.group(1), line);
        }
        return new RawRow("", "", line);
    }

    /**
     * The divider between the time and event columns is the interior vertical line with the most total length; day
     * header rows span both columns, so it is broken but still dominant.
     */
    private static Float findDivider(List<Segment> verticals, float left, float right) {
        Map<Float, Double> lengthByX = new LinkedHashMap<>();
        for (Segment r : verticals) {
            float x = r.pos();
            if (x - left < SNAP * 4 || right - x < SNAP * 4) {
                continue;
            }
            Float key = lengthByX.keySet().stream().filter(k -> Math.abs(k - x) <= SNAP).findFirst().orElse(x);
            lengthByX.merge(key, (double) r.length(), Double::sum);
        }
        return lengthByX.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /**
     * Vertical bands {@code [top, bottom]} between consecutive horizontal rulings that cross {@code x}. The page's top
     * and bottom edges count as rulings, so text printed above or below the grid (headers, footnotes) is kept.
     */
    private static List<float[]> bandsAt(List<Segment> horizontals, float x, float pageBottom) {
        List<Float> ys = new ArrayList<>(List.of(0f, pageBottom));
        horizontals.stream()
                .filter(r -> r.start() - SNAP <= x && x <= r.end() + SNAP)
                .map(Segment::pos)
                .forEach(ys::add);
        ys.sort(null);
        List<Float> lines = new ArrayList<>();
        for (Float y : ys) {
            if (lines.isEmpty() || y - lines.getLast() > SNAP) {
                lines.add(y);
            }
        }
        List<float[]> bands = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i) - lines.get(i - 1) >= MIN_BAND) {
                bands.add(new float[]{lines.get(i - 1), lines.get(i)});
            }
        }
        return bands;
    }

    /** Text fully inside the rectangle, one line per visual line, whitespace normalised. */
    private static String text(Page page, float top, float left, float right, float bottom) {
        Rectangle cell = new Rectangle(top - PAD, left - PAD, right - left + 2 * PAD, bottom - top + 2 * PAD);
        List<String> out = new ArrayList<>();
        for (List<TextElement> line : visualLines(page.getText(cell))) {
            // Separate chunks are runs of text with a visible gap between them (e.g. "Name [FREE]"), so keep a space.
            String text = TextElement.mergeWords(line).stream()
                    .map(TextChunk::getText)
                    .collect(Collectors.joining(" "))
                    .replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return String.join("\n", out);
    }

    /**
     * Groups glyphs into visual lines, each sorted left to right. Glyphs whose vertical centre falls within a line's
     * extent join it, so bold/italic runs and inline badges sitting slightly higher or lower stay in reading order.
     */
    private static List<List<TextElement>> visualLines(List<TextElement> elements) {
        List<TextElement> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparingDouble(TextElement::getCenterY));
        List<List<TextElement>> lines = new ArrayList<>();
        double lineBottom = Double.NEGATIVE_INFINITY;
        for (TextElement e : sorted) {
            if (lines.isEmpty() || e.getCenterY() > lineBottom) {
                lines.add(new ArrayList<>());
                lineBottom = e.getBottom();
            } else {
                lineBottom = Math.max(lineBottom, e.getBottom());
            }
            lines.getLast().add(e);
        }
        lines.forEach(line -> line.sort(Comparator.comparingDouble(TextElement::getLeft)));
        return lines;
    }
}
