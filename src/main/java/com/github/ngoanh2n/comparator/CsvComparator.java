package com.github.ngoanh2n.comparator;

import com.github.ngoanh2n.Commons;
import com.univocity.parsers.common.ParsingContext;
import com.univocity.parsers.common.processor.RowProcessor;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import org.mozilla.universalchardet.UniversalDetector;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This class handles to compare {@linkplain CsvComparisonSource#exp()} and {@linkplain CsvComparisonSource#act()}.
 *
 * @author Ho Huu Ngoan (ngoanh2n@gmail.com)
 * @version 1.0.0
 * @since 2020-01-06
 */
public class CsvComparator {
    private final CsvComparisonSource source;
    private final CsvComparisonOptions options;
    private final CsvComparisonVisitor visitor;

    private CsvComparator(@Nonnull CsvComparisonSource source,
                          @Nonnull CsvComparisonOptions options) {
        this(source, options, new DefaultCsvComparisonVisitor());
    }

    private CsvComparator(@Nonnull CsvComparisonSource source,
                          @Nonnull CsvComparisonOptions options,
                          @Nonnull CsvComparisonVisitor visitor) {
        this.source = checkNotNull(source, "source cannot be null");
        this.options = checkNotNull(options, "source cannot be null");
        this.visitor = checkNotNull(visitor, "source cannot be null");
    }

    public static CsvComparisonResult compare(@Nonnull CsvComparisonSource source,
                                              @Nonnull CsvComparisonOptions options) {
        return new CsvComparator(source, options).compare();
    }

    public static CsvComparisonResult compare(@Nonnull CsvComparisonSource source,
                                              @Nonnull CsvComparisonOptions options,
                                              @Nonnull CsvComparisonVisitor visitor) {
        return new CsvComparator(source, options, visitor).compare();
    }

    @Nonnull
    private CsvComparisonResult compare() {
        Collector collector = new Collector();
        visitor.comparisonStarted(source, options);
        CsvParserSettings settings = getSettings();

        String[] headers = getHeaders(settings);
        int index = options.identityColumnIndex();
        List<String[]> expRows = read(source.exp(), getEncoding(source.exp()), settings);
        Map<String, String[]> expMap = expRows.stream().collect(Collectors.toMap(r -> r[index], r -> r, (a, b) -> b));

        settings.setProcessor(new RowProcessor() {
            @Override
            public void processStarted(ParsingContext context) { /* No implementation necessary */ }

            @Override
            public void rowProcessed(String[] actRow, ParsingContext context) {
                String[] expRow = expMap.get(actRow[index]);

                if (expRow == null) {
                    visitor.rowInserted(actRow, headers, options);
                    collector.rowInserted(actRow, headers, options);
                } else {
                    if (Arrays.equals(actRow, expRow)) {
                        visitor.rowKept(actRow, headers, options);
                        collector.rowKept(actRow, headers, options);
                    } else {
                        List<HashMap<String, String>> diffs = getDiffs(headers, expRow, actRow);
                        visitor.rowModified(actRow, headers, options, diffs);
                        collector.rowModified(actRow, headers, options, diffs);
                    }
                    expMap.remove(actRow[index]);
                }
            }

            @Override
            public void processEnded(ParsingContext context) { /* No implementation necessary */ }
        });
        new CsvParser(settings).parse(source.act(), getEncoding(source.act()));

        if (expMap.size() > 0) {
            for (Map.Entry<String, String[]> left : expMap.entrySet()) {
                String[] row = left.getValue();
                visitor.rowDeleted(row, headers, options);
                collector.rowDeleted(row, headers, options);
            }
        }

        Result result = new Result(collector);
        visitor.comparisonFinished(source, options, result);
        return result;
    }

    private CsvParserSettings getSettings() {
        Commons.createDirectory(options.resultOptions().location());
        return options.parserSettings();
    }

    private Charset getEncoding(File file) {
        try {
            return options.encoding() != null
                    ? options.encoding()
                    : Charset.forName(UniversalDetector.detectCharset(file));
        } catch (IOException ignored) {
            // Can't happen
            return StandardCharsets.UTF_8;
        }
    }

    private String[] getHeaders(CsvParserSettings settings) {
        if (settings.isHeaderExtractionEnabled()) {
            String[] headers = new String[0];
            settings.setHeaderExtractionEnabled(false);
            List<String[]> expRows = read(source.exp(), getEncoding(source.exp()), settings);

            if (expRows.size() > 1) {
                headers = expRows.get(0);
            }
            settings.setHeaderExtractionEnabled(true);
            return headers;
        }
        return new String[0];
    }

    private List<HashMap<String, String>> getDiffs(String[] headers, String[] expRow, String[] actRow) {
        List<HashMap<String, String>> diffs = new ArrayList<>();

        for (int index = 0; index < headers.length; index++) {
            String header = headers[index];
            String expCell = expRow[index];
            String actCell = actRow[index];

            if (!expCell.equals(actCell)) {
                diffs.add(new HashMap<String, String>() {{
                    put("column", header);
                    put("expCell", expCell);
                    put("actCell", actCell);
                }});
            }
        }
        return diffs;
    }

    private final static class Result implements CsvComparisonResult {

        private final Collector collector;

        private Result(Collector collector) {
            this.collector = collector;
        }

        @Override
        public boolean isDeleted() {
            return collector.isDeleted;
        }

        @Override
        public boolean isInserted() {
            return collector.isInserted;
        }

        @Override
        public boolean isModified() {
            return collector.isModified;
        }

        @Override
        public List<String[]> rowsKept() {
            return collector.rowsKept;
        }

        @Override
        public List<String[]> rowsDeleted() {
            return collector.rowsDeleted;
        }

        @Override
        public List<String[]> rowsInserted() {
            return collector.rowsInserted;
        }

        @Override
        public List<String[]> rowsModified() {
            return collector.rowsModified;
        }

        @Override
        public boolean isDifferent() {
            return isDeleted() || isInserted() || isModified();
        }
    }

    private final static class Collector implements CsvComparisonVisitor {
        private boolean isDeleted = false;
        private boolean isInserted = false;
        private boolean isModified = false;
        private final List<String[]> rowsKept = new ArrayList<>();
        private final List<String[]> rowsDeleted = new ArrayList<>();
        private final List<String[]> rowsInserted = new ArrayList<>();
        private final List<String[]> rowsModified = new ArrayList<>();

        @Override
        public void comparisonStarted(CsvComparisonSource source, CsvComparisonOptions options) { /* No implementation necessary */ }

        @Override
        public void rowKept(String[] row, String[] headers, CsvComparisonOptions options) {
            rowsKept.add(row);
        }

        @Override
        public void rowDeleted(String[] row, String[] headers, CsvComparisonOptions options) {
            isDeleted = true;
            rowsDeleted.add(row);
        }

        @Override
        public void rowInserted(String[] row, String[] headers, CsvComparisonOptions options) {
            isInserted = true;
            rowsInserted.add(row);
        }

        @Override
        public void rowModified(String[] row, String[] headers, CsvComparisonOptions options, List<HashMap<String, String>> diffs) {
            isModified = true;
            rowsModified.add(row);
        }

        @Override
        public void comparisonFinished(CsvComparisonSource source, CsvComparisonOptions options, CsvComparisonResult result) { /* No implementation necessary */ }
    }

    static List<String[]> read(@Nonnull File csv, @Nonnull Charset encoding, @Nonnull CsvParserSettings settings) {
        return new CsvParser(settings).parseAll(csv, encoding);
    }
}
