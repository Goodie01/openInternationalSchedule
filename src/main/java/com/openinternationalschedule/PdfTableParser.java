package com.openinternationalschedule;

import org.apache.pdfbox.pdmodel.PDDocument;
import technology.tabula.Cell;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.TextElement;

import com.openinternationalschedule.RulingExtractor.Segment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the two-column schedule tables out of the PDF.
 * <p>
 * Tabula's own table extraction loses the text of time cells that are merged across several event rows, so the grid
 * is rebuilt here from the visible border lines ({@link RulingExtractor}): the event column's horizontal lines give
 * one band per event, the time column's lines give (possibly taller) bands per time slot, and each event band takes
 * the time of the slot that contains it. Tabula is only used for text-in-rectangle lookups.
 */
public final class PdfTableParser {

    static final Pattern DAY_HEADER =
            Pattern.compile("^(Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day,\\s+\\w+\\s+\\d{1,2},\\s+\\d{4}$");

    /** Rulings closer than this (points) are treated as the same line. */
    private static final float SNAP = 1.5f;
    /** Bands shorter than this are slivers between double lines, not rows. */
    private static final float MIN_BAND = 4f;
    /** Slack when collecting text inside a cell, since glyph boxes can touch the border. */
    private static final float PAD = 1f;

    private PdfTableParser() {
    }

    public static List<RawRow> parse(PDDocument doc) throws IOException {
        List<RawRow> rows = new ArrayList<>();
        try (ObjectExtractor extractor = new ObjectExtractor(doc)) {
            for (int pageNo = 1; pageNo <= doc.getNumberOfPages(); pageNo++) {
                Page page = extractor.extract(pageNo);
                RulingExtractor.Lines lines = RulingExtractor.extract(doc.getPage(pageNo - 1));
                float mid = (float) page.getWidth() / 2;
                rows.addAll(parseColumn(page, lines, 0, mid));
                rows.addAll(parseColumn(page, lines, mid, (float) page.getWidth()));
            }
        }
        return rows;
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
                .toList();
        List<float[]> eventBands = bandsAt(horizontals, (divider + right) / 2);
        List<float[]> timeBands = bandsAt(horizontals, (left + divider) / 2);

        List<RawRow> rows = new ArrayList<>();
        for (float[] band : eventBands) {
            String full = text(page, band[0], left, right, band[1]);
            if (full.isEmpty()) {
                continue;
            }
            if (DAY_HEADER.matcher(full).matches()) {
                rows.add(new RawRow("", "", full));
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

    /** Vertical bands {@code [top, bottom]} between consecutive horizontal rulings that cross {@code x}. */
    private static List<float[]> bandsAt(List<Segment> horizontals, float x) {
        List<Float> ys = horizontals.stream()
                .filter(r -> r.start() - SNAP <= x && x <= r.end() + SNAP)
                .map(Segment::pos)
                .sorted()
                .toList();
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
        Cell cell = new Cell(top - PAD, left - PAD, right - left + 2 * PAD, bottom - top + 2 * PAD);
        List<TextElement> elements = new ArrayList<>(page.getText(cell));
        elements.sort(Comparator.comparingDouble(TextElement::getTop).thenComparingDouble(TextElement::getLeft));
        cell.setTextElements(TextElement.mergeWords(elements));
        return cell.getText().lines()
                .map(line -> line.replace(' ', ' ').replaceAll("\\s+", " ").strip())
                .filter(line -> !line.isEmpty())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
