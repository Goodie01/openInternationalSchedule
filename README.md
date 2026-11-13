# The Open: Schedule in Your Time Zone

An unofficial, auto-updating copy of [The Open World Swing Dance Championships](https://theopenswing.com/schedules/)
weekend schedule. The official schedule is a PDF with all times in Los Angeles time. This site shows the same
schedule in each visitor's local time zone, and visitors can pick any other zone.

## How it works

1. A GitHub Actions workflow (`.github/workflows/update.yml`) runs daily, on every push to `main`, and on demand.
2. A small Java CLI:
   - finds the "WEEKEND SCHEDULE (PDF)" link on the schedules page (`SourceLocator`)
   - downloads the PDF
   - rebuilds the time/event tables from the PDF's visible cell borders (`RulingExtractor`, `PdfTableParser`); both the
     original side-by-side weekend layout and the single-column "full schedule" layout are handled
   - turns the rows into dated events in `America/Los_Angeles` (`ScheduleBuilder`)
   - sanity-checks the result (`Validator`)
   - writes `site/schedule.json`
3. The `site/` folder (static HTML/JS, no build step) is deployed to GitHub Pages. The browser does the time zone
   conversion with `Intl.DateTimeFormat`.

If the update fails (for example, the PDF layout changes for next year), nothing is deployed and the last good
schedule stays live. The workflow opens an issue labelled `schedule-parse-failure` with the error. The next
successful run closes it.

## One-time setup

1. Push this repository to GitHub.
2. **Settings → Pages → Build and deployment → Source: GitHub Actions.**
3. **Actions → Update schedule → Run workflow** to publish the first time.

GitHub disables scheduled workflows after 60 days without repository activity. If that happens, re-enable the
workflow from the Actions tab, or push any commit, before the next event.

## Local development

Requires JDK 21+. The Maven wrapper downloads Maven itself.

```sh
./mvnw package                                          # build + tests (uses src/test/resources fixture PDF)
java -jar target/schedule.jar                           # live fetch -> site/schedule.json
java -jar target/schedule.jar --pdf some.pdf            # parse a local PDF instead
python3 -m http.server -d site                          # then open http://localhost:8000/?tz=Asia/Tokyo
```

When the PDF changes, add it under `src/test/resources/` and extend `ScheduleParsingTest` with a few rows checked by
eye against the PDF.
