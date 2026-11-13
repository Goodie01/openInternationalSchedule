package com.openinternationalschedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/**
 * Fetches the schedule PDF, parses it, and writes {@code schedule.json} for the static site.
 * <p>
 * Usage: {@code java -jar schedule.jar [--out site/schedule.json] [--page URL] [--pdf local.pdf]}
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (ScheduleParseException | IllegalArgumentException | IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Caused by: " + e.getCause());
            }
            System.exit(1);
        }
    }

    static void run(String[] args) throws IOException {
        Path out = Path.of("site/schedule.json");
        String pageUrl = SourceLocator.DEFAULT_PAGE;
        Path localPdf = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(requireValue(args, ++i));
                case "--page" -> pageUrl = requireValue(args, ++i);
                case "--pdf" -> localPdf = Path.of(requireValue(args, ++i));
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        ScheduleSource source;
        byte[] pdf;
        if (localPdf != null) {
            source = new ScheduleSource(null, null, null, null);
            pdf = Files.readAllBytes(localPdf);
        } else {
            source = SourceLocator.locate(pageUrl);
            System.out.println("Schedule PDF: " + source.viewUrl());
            pdf = download(source.downloadUrl());
        }

        Schedule schedule = parse(pdf, source);
        Files.createDirectories(out.toAbsolutePath().getParent());
        mapper().writeValue(out.toFile(), schedule);
        int events = schedule.days().stream().mapToInt(d -> d.events().size()).sum();
        System.out.printf("Wrote %s: %d days, %d events%n", out, schedule.days().size(), events);
    }

    static Schedule parse(byte[] pdf, ScheduleSource source) throws IOException {
        return parse(pdf, source, LocalDate.now(ScheduleBuilder.SOURCE_ZONE));
    }

    /** @param today reference date for working out the year of day headers that don't print one */
    static Schedule parse(byte[] pdf, ScheduleSource source, LocalDate today) throws IOException {
        List<RawRow> rows;
        try (PDDocument doc = PDDocument.load(pdf)) {
            rows = PdfTableParser.parse(doc);
        } catch (IOException e) {
            throw new ScheduleParseException("Could not read the schedule PDF", e);
        }
        ScheduleBuilder.Result result = ScheduleBuilder.build(rows, today);
        Validator.validate(result);
        return new Schedule(
                Instant.now(),
                ScheduleBuilder.SOURCE_ZONE.getId(),
                source.pageUrl(),
                source.viewUrl(),
                sha256(pdf),
                source.sourceUpdated(),
                result.notes(),
                result.days());
    }

    static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    private static byte[] download(String url) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", SourceLocator.USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        }
        byte[] body = response.body();
        if (response.statusCode() != 200) {
            throw new ScheduleParseException("HTTP " + response.statusCode() + " downloading " + url);
        }
        if (body.length < 5 || !new String(body, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new ScheduleParseException("Download from " + url + " is not a PDF (Drive may require sign-in or "
                    + "a virus-scan confirmation)");
        }
        return body;
    }

    private static String requireValue(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException(args[i - 1] + " needs a value");
        }
        return args[i];
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
