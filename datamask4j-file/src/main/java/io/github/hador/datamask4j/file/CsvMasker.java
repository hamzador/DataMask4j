package io.github.hador.datamask4j.file;

import io.github.hador.datamask4j.core.DataMasker;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.DuplicateHeaderMode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Streaming RFC4180-style CSV masking. On error, discard all partial output. */
public final class CsvMasker {
    private static final CSVFormat INPUT = CSVFormat.RFC4180.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
            .setAllowMissingColumnNames(false)
            .get();
    private final DataMasker masker;
    private final FileMaskingLimits limits;

    public CsvMasker(DataMasker masker) {
        this(masker, FileMaskingLimits.defaults());
    }

    public CsvMasker(DataMasker masker, FileMaskingLimits limits) {
        this.masker = Objects.requireNonNull(masker, "masker");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Returns count of records excluding the header. Does not close caller streams. */
    public long mask(Reader source, Writer destination) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        try (var parser = INPUT.parse(BoundedStreams.reader(source, limits.maxInputChars()))) {
            var headers = parser.getHeaderNames();
            MaskingChecks.fields(headers.size(), limits);
            headers.forEach(header -> MaskingChecks.value(header, limits));
            var outputFields = masker.outputFields(headers);
            if (outputFields.isEmpty()) {
                throw new FileMaskingException("CSV requires at least one retained column");
            }
            outputFields.forEach(MaskingChecks::spreadsheetCell);
            var bounded = BoundedStreams.writer(destination, limits.maxOutputChars());
            try (var printer = new CSVPrinter(bounded, CSVFormat.RFC4180)) {
                printer.printRecord(outputFields);
                long count = 0;
                for (var row : parser) {
                    MaskingChecks.recordCount(count, limits);
                    if (row.size() != headers.size()) {
                        throw new FileMaskingException("CSV row width mismatch");
                    }
                    var input = new LinkedHashMap<String, String>();
                    for (int column = 0; column < headers.size(); column++) {
                        String value = row.get(column);
                        MaskingChecks.value(value, limits);
                        input.put(headers.get(column), value);
                    }
                    var masked = masker.mask(input);
                    var values = new ArrayList<String>(outputFields.size());
                    for (String field : outputFields) {
                        String value = masked.get(field);
                        MaskingChecks.value(value, limits);
                        MaskingChecks.spreadsheetCell(value);
                        values.add(value);
                    }
                    printer.printRecord(values);
                    count++;
                }
                printer.flush();
                return count;
            }
        } catch (IOException | RuntimeException ignored) {
            // Third-party parser, writer and masking exceptions may contain sensitive data.
            throw new FileMaskingException("CSV masking failed; discard partial output");
        }
    }
}
