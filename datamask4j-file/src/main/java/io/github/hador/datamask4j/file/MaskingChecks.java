package io.github.hador.datamask4j.file;

import java.util.Map;

final class MaskingChecks {
    private MaskingChecks() {}

    static void value(String value, FileMaskingLimits limits) {
        if (value != null && value.length() > limits.maxValueChars()) {
            throw new FileMaskingException("Field length limit exceeded");
        }
    }

    static void fields(int count, FileMaskingLimits limits) {
        if (count > limits.maxFields()) {
            throw new FileMaskingException("Field count limit exceeded");
        }
    }

    static void recordCount(long count, FileMaskingLimits limits) {
        if (count >= limits.maxRecords()) {
            throw new FileMaskingException("Record count limit exceeded");
        }
    }

    static void record(Map<String, String> record, FileMaskingLimits limits) {
        fields(record.size(), limits);
        record.forEach((name, value) -> {
            value(name, limits);
            value(value, limits);
        });
    }

    static void spreadsheetCell(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        // Conservative guard against common spreadsheet formula prefixes, including
        // leading whitespace and fullwidth variants. CSV is not a sandbox.
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (codePoint == '\t' || codePoint == '\r' || codePoint == '\n'
                    || Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                throw new FileMaskingException("Unsafe spreadsheet cell");
            }
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                if ("=+-@＝＋－＠".indexOf(codePoint) >= 0) {
                    throw new FileMaskingException("Unsafe spreadsheet cell");
                }
                return;
            }
            index += Character.charCount(codePoint);
        }
    }
}
