package com.openinternationalschedule;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects the visible, axis-aligned table border segments on a page.
 * <p>
 * Tabula also turns the edges of every background fill into rulings, which puts phantom lines through merged cells.
 * Here only dark strokes and dark hairline fills (another common way of drawing borders) count, plus one more
 * pattern: a cell drawn as a light rectangle painted over a slightly offset dark rectangle of the same size, where
 * the visible border is the dark sliver left showing. Such a light cell contributes its four edges. Plain light
 * fills still don't. Coordinates are returned top-down like Tabula's, so they can be mixed with Tabula text
 * positions.
 */
final class RulingExtractor extends PDFGraphicsStreamEngine {

    /** An axis-aligned segment: {@code pos} is y for horizontals and x for verticals; {@code start < end}. */
    record Segment(float pos, float start, float end) {
        float length() {
            return end - start;
        }
    }

    record Lines(List<Segment> horizontals, List<Segment> verticals) {
    }

    private static final float AXIS_TOLERANCE = 0.5f;
    private static final float HAIRLINE = 2f;
    private static final float DARK = 0.5f;
    /** How far a dark backing rectangle may be offset from, or differ in size to, the light cell painted over it. */
    private static final float BACKING_TOLERANCE = 2f;

    private final float pageTop;
    private final float pageLeft;
    /** Dark filled rectangles painted since the last light fill, as {@code [minX, minY, maxX, maxY]}. */
    private final List<float[]> darkRects = new ArrayList<>();
    private final List<float[]> pending = new ArrayList<>();
    private final List<Segment> horizontals = new ArrayList<>();
    private final List<Segment> verticals = new ArrayList<>();
    private Point2D.Float current;
    private Point2D.Float subpathStart;

    private RulingExtractor(PDPage page) {
        super(page);
        PDRectangle box = page.getCropBox();
        this.pageTop = box.getUpperRightY();
        this.pageLeft = box.getLowerLeftX();
    }

    static Lines extract(PDPage page) throws IOException {
        RulingExtractor extractor = new RulingExtractor(page);
        extractor.processPage(page);
        return new Lines(List.copyOf(extractor.horizontals), List.copyOf(extractor.verticals));
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        moveTo((float) p0.getX(), (float) p0.getY());
        lineTo((float) p1.getX(), (float) p1.getY());
        lineTo((float) p2.getX(), (float) p2.getY());
        lineTo((float) p3.getX(), (float) p3.getY());
        closePath();
    }

    @Override
    public void moveTo(float x, float y) {
        current = new Point2D.Float(x, y);
        subpathStart = current;
    }

    @Override
    public void lineTo(float x, float y) {
        if (current != null) {
            pending.add(new float[]{current.x, current.y, x, y});
        }
        current = new Point2D.Float(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        current = new Point2D.Float(x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
        return current;
    }

    @Override
    public void closePath() {
        if (current != null && subpathStart != null) {
            lineTo(subpathStart.x, subpathStart.y);
        }
    }

    @Override
    public void endPath() {
        reset();
    }

    @Override
    public void strokePath() {
        if (isDark(getGraphicsState().getStrokingColor())) {
            pending.forEach(this::addSegment);
        }
        reset();
    }

    @Override
    public void fillPath(int windingRule) {
        addFill();
        reset();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        addFill();
        strokePath();
    }

    private void addFill() {
        if (isDark(getGraphicsState().getNonStrokingColor())) {
            addHairlineFill();
            float[] rect = pendingRectangle();
            if (rect != null) {
                darkRects.add(rect);
            }
        } else {
            addBackedCell();
            darkRects.clear();
        }
    }

    @Override
    public void drawImage(PDImage pdImage) {
    }

    @Override
    public void clip(int windingRule) {
    }

    @Override
    public void shadingFill(COSName shadingName) {
    }

    /** A filled rectangle thinner than a hairline is a border drawn as a fill; record it as its centre line. */
    private void addHairlineFill() {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] s : pending) {
            minX = Math.min(minX, Math.min(s[0], s[2]));
            maxX = Math.max(maxX, Math.max(s[0], s[2]));
            minY = Math.min(minY, Math.min(s[1], s[3]));
            maxY = Math.max(maxY, Math.max(s[1], s[3]));
        }
        if (pending.isEmpty()) {
            return;
        }
        if (maxY - minY <= HAIRLINE && maxX - minX > HAIRLINE) {
            float y = (minY + maxY) / 2;
            addSegment(new float[]{minX, y, maxX, y});
        } else if (maxX - minX <= HAIRLINE && maxY - minY > HAIRLINE) {
            float x = (minX + maxX) / 2;
            addSegment(new float[]{x, minY, x, maxY});
        }
    }

    /** A light rectangle painted over a dark one of (nearly) the same size is a bordered cell. */
    private void addBackedCell() {
        float[] cell = pendingRectangle();
        if (cell == null) {
            return;
        }
        boolean backed = darkRects.stream().anyMatch(dark -> {
            for (int i = 0; i < 4; i++) {
                if (Math.abs(dark[i] - cell[i]) > BACKING_TOLERANCE) {
                    return false;
                }
            }
            return true;
        });
        if (backed) {
            pending.forEach(this::addSegment);
        }
    }

    /** The pending path as {@code [minX, minY, maxX, maxY]} if it is a single axis-aligned rectangle, else null. */
    private float[] pendingRectangle() {
        if (pending.size() != 4 || !pending.stream().allMatch(RulingExtractor::isAxisAligned)) {
            return null;
        }
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] s : pending) {
            minX = Math.min(minX, Math.min(s[0], s[2]));
            maxX = Math.max(maxX, Math.max(s[0], s[2]));
            minY = Math.min(minY, Math.min(s[1], s[3]));
            maxY = Math.max(maxY, Math.max(s[1], s[3]));
        }
        if (maxX - minX <= HAIRLINE || maxY - minY <= HAIRLINE) {
            return null;
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    private static boolean isAxisAligned(float[] s) {
        return Math.abs(s[0] - s[2]) <= AXIS_TOLERANCE || Math.abs(s[1] - s[3]) <= AXIS_TOLERANCE;
    }

    private void addSegment(float[] s) {
        float x0 = s[0] - pageLeft, x1 = s[2] - pageLeft;
        float y0 = pageTop - s[1], y1 = pageTop - s[3];
        if (Math.abs(y0 - y1) <= AXIS_TOLERANCE && Math.abs(x0 - x1) > AXIS_TOLERANCE) {
            horizontals.add(new Segment((y0 + y1) / 2, Math.min(x0, x1), Math.max(x0, x1)));
        } else if (Math.abs(x0 - x1) <= AXIS_TOLERANCE && Math.abs(y0 - y1) > AXIS_TOLERANCE) {
            verticals.add(new Segment((x0 + x1) / 2, Math.min(y0, y1), Math.max(y0, y1)));
        }
    }

    private void reset() {
        pending.clear();
        current = null;
        subpathStart = null;
    }

    private static boolean isDark(PDColor color) {
        try {
            float[] rgb = color.getColorSpace().toRGB(color.getComponents());
            return 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2] < DARK;
        } catch (IOException | RuntimeException e) {
            // Patterns and other exotic colour spaces are never table borders here.
            return false;
        }
    }
}
