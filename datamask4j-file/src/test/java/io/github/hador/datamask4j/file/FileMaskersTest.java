package io.github.hador.datamask4j.file;

import io.github.hador.datamask4j.core.DataMasker;
import io.github.hador.datamask4j.core.FieldRule;
import io.github.hador.datamask4j.core.MaskingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileMaskersTest {
    private DataMasker masker;

    @BeforeEach
    void setUp() {
        masker = new DataMasker(MaskingPolicy.builder()
                .field("id", FieldRule.keep())
                .field("email", FieldRule.redact())
                .field("notes", FieldRule.drop())
                .build());
    }

    @Test
    void shouldMaskCsvWithQuotedAndMultilineInput() {
        var output = new StringWriter();
        assertEquals(2, new CsvMasker(masker).mask(new StringReader(
                "id,email,notes\r\nC-1,private@example.test,\"line1\nline2\"\r\n\"C,2\",other@example.test,secret\r\n"), output));
        assertEquals("id,email\r\nC-1,[REDACTED]\r\n\"C,2\",[REDACTED]\r\n", output.toString());
    }

    @Test
    void shouldValidateCsvHeaderEvenWithoutRecords() {
        var output = new StringWriter();
        assertEquals(0, new CsvMasker(masker).mask(new StringReader("id,email,notes\n"), output));
        assertEquals("id,email\r\n", output.toString());
        for (String invalid : List.of("", "id,email\n", "id,email,notes,secret\n", "id,id,notes\n")) {
            var rejected = new StringWriter();
            assertThrows(FileMaskingException.class, () -> new CsvMasker(masker).mask(new StringReader(invalid), rejected));
            assertEquals("", rejected.toString());
        }
    }

    @Test
    void shouldRejectCsvRowWidthAndMalformedQuotes() {
        for (String row : List.of("C-1,email", "C-1,email,notes,unexpected", "C-1,\"unclosed")) {
            assertThrows(FileMaskingException.class, () -> new CsvMasker(masker).mask(
                    new StringReader("id,email,notes\n" + row), new StringWriter()));
        }
    }

    @Test
    void shouldRejectCommonFormulaPrefixesInRetainedCells() {
        for (String value : List.of("=1+1", " +42", "-42", "@SUM(A1)", "\t=1", "＝1", "　＋1")) {
            var output = new StringWriter();
            assertThrows(FileMaskingException.class, () -> new CsvMasker(masker).mask(
                    new StringReader("id,email,notes\n\"" + value + "\",secret,secret\n"), output));
            assertFalse(output.toString().contains(value));
        }
    }

    @Test
    void shouldAllowFormulaInputWhenItIsDiscardedOrRedacted() {
        var output = new StringWriter();
        assertEquals(1, new CsvMasker(masker).mask(
                new StringReader("id,email,notes\nC-1,=1+1,@SUM(A1)\n"), output));
        assertEquals("id,email\r\nC-1,[REDACTED]\r\n", output.toString());
    }

    @Test
    void shouldRejectCsvWhenAllFieldsAreDropped() {
        var drop = new DataMasker(MaskingPolicy.builder().field("id", FieldRule.drop()).build());
        assertThrows(FileMaskingException.class,
                () -> new CsvMasker(drop).mask(new StringReader("id\n"), new StringWriter()));
    }

    @Test
    void shouldMaskJsonPreserveNullAndRemoveFields() {
        var output = new StringWriter();
        assertEquals(2, new JsonMasker(masker).mask(new StringReader("""
                [{"id":"C-1","email":"private@example.test","notes":"secret"},
                 {"id":"C-2","email":null,"notes":null}]
                """), output));
        assertEquals("[{\"id\":\"C-1\",\"email\":\"[REDACTED]\"},{\"id\":\"C-2\",\"email\":null}]", output.toString());
    }

    @Test
    void shouldRejectDuplicateUnknownMissingAndNonStringJsonFields() {
        for (String input : List.of(
                "[{\"id\":\"1\",\"id\":\"2\",\"email\":null,\"notes\":null}]",
                "[{\"id\":\"1\",\"email\":null}]",
                "[{\"id\":\"1\",\"email\":null,\"notes\":null,\"unknownSecret\":\"rawSecret\"}]",
                "[{\"id\":1,\"email\":null,\"notes\":null}]",
                "[{\"id\":{},\"email\":null,\"notes\":null}]",
                "[{\"id\":true,\"email\":null,\"notes\":null}]",
                "[{\"id\":[],\"email\":null,\"notes\":null}]",
                "{}","[] true","[null]","[","")) {
            var exception = assertThrows(FileMaskingException.class,
                    () -> new JsonMasker(masker).mask(new StringReader(input), new StringWriter()));
            assertNull(exception.getCause());
            assertFalse(exception.getMessage().contains("rawSecret"));
            assertFalse(exception.getMessage().contains("unknownSecret"));
        }
    }

    @Test
    void shouldRespectRecordFieldInputAndOutputLimits() {
        String json = "[{\"id\":\"1\",\"email\":null,\"notes\":null}]";
        String csv = "id,email,notes\n1,a,b\n";
        for (var limits : List.of(
                new FileMaskingLimits(5, 1000, 10, 10, 20),
                new FileMaskingLimits(1000, 5, 10, 10, 20),
                new FileMaskingLimits(1000, 1000, 10, 2, 20),
                new FileMaskingLimits(1000, 1000, 10, 10, 2))) {
            assertThrows(FileMaskingException.class,
                    () -> new JsonMasker(masker, limits).mask(new StringReader(json), new StringWriter()));
            assertThrows(FileMaskingException.class,
                    () -> new CsvMasker(masker, limits).mask(new StringReader(csv), new StringWriter()));
        }
        var one = new FileMaskingLimits(1000, 1000, 1, 10, 20);
        assertThrows(FileMaskingException.class, () -> new CsvMasker(masker, one).mask(
                new StringReader(csv + "2,a,b\n"), new StringWriter()));
        assertThrows(FileMaskingException.class, () -> new JsonMasker(masker, one).mask(
                new StringReader(json.substring(0, json.length() - 1) + "," + json.substring(1)), new StringWriter()));
        assertThrows(IllegalArgumentException.class, () -> new FileMaskingLimits(0, 1, 1, 1, 1));
    }

    @Test
    void shouldAcceptExactInputAndOutputLimits() {
        var limits = new FileMaskingLimits(2, 2, 1, 3, 10);
        var output = new StringWriter();
        assertEquals(0, new JsonMasker(masker, limits).mask(new StringReader("[]"), output));
        assertEquals("[]", output.toString());
        assertThrows(FileMaskingException.class, () -> new JsonMasker(masker, limits).mask(new StringReader("[] "), new StringWriter()));
    }

    @Test
    void shouldKeepCallerStreamsOpenAndSanitiseIoFailures() {
        class TrackingReader extends StringReader {
            boolean closed;
            TrackingReader(String value) { super(value); }
            @Override public void close() { closed = true; super.close(); }
        }
        class TrackingWriter extends StringWriter {
            boolean closed;
            @Override public void close() { closed = true; }
        }
        var source = new TrackingReader("[]");
        var target = new TrackingWriter();
        new JsonMasker(masker).mask(source, target);
        assertFalse(source.closed);
        assertFalse(target.closed);
        var failing = new Writer() {
            @Override public void write(char[] c, int o, int n) throws IOException { throw new IOException("raw-secret"); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var exception = assertThrows(FileMaskingException.class,
                () -> new JsonMasker(masker).mask(new StringReader("[]"), failing));
        assertNull(exception.getCause());
        assertFalse(exception.getMessage().contains("raw-secret"));
    }
}
