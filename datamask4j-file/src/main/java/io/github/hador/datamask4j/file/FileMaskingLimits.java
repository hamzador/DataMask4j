package io.github.hador.datamask4j.file;

/** Bounds measured in UTF-16 code units and record/field counts, not bytes. */
public record FileMaskingLimits(
        long maxInputChars, long maxOutputChars, long maxRecords, int maxFields, int maxValueChars) {
    public FileMaskingLimits {
        if (maxInputChars <= 0 || maxOutputChars <= 0 || maxRecords <= 0
                || maxFields <= 0 || maxValueChars <= 0) {
            throw new IllegalArgumentException("Masking limits must be positive");
        }
    }

    public static FileMaskingLimits defaults() {
        return new FileMaskingLimits(8_388_608, 8_388_608, 100_000, 128, 65_536);
    }
}
