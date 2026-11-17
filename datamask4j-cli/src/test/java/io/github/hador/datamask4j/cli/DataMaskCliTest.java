package io.github.hador.datamask4j.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DataMaskCliTest {
    @TempDir Path directory;
    private Path input;
    private Path output;
    private Path policy;
    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        input = directory.resolve("input.csv");
        output = directory.resolve("masked.csv");
        policy = directory.resolve("policy.yml");
        Files.writeString(input, "email,notes\nprivate@example.test,raw\n");
        Files.writeString(policy, "fields:\n  email:\n    strategy: redact\n  notes:\n    strategy: drop\n");
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
    }

    private int run(String... args) {
        return DataMaskCli.run(args, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    private String[] args(String format, String... extra) {
        var list = new java.util.ArrayList<>(java.util.List.of("mask", "--format", format,
                "--input", input.toString(), "--output", output.toString(), "--policy", policy.toString()));
        list.addAll(java.util.List.of(extra));
        return list.toArray(String[]::new);
    }

    @Test
    void shouldPublishMaskedCsvWithoutChangingInput() throws Exception {
        String original = Files.readString(input);
        assertEquals(0, run(args("csv")));
        assertEquals("email\r\n[REDACTED]\r\n", Files.readString(output));
        assertEquals(original, Files.readString(input));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(output));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Masked records: 1"));
        assertNoStagingFiles();
    }

    @Test
    void shouldPublishMaskedJsonAndPreserveNulls() throws Exception {
        Files.writeString(input, "[{\"email\":null,\"notes\":\"secret\"}]");
        assertEquals(0, run(args("json")));
        assertEquals("[{\"email\":null}]", Files.readString(output));
    }

    @Test
    void shouldRejectExistingOutputAndNeverOverwriteSourceOrHardLink() throws Exception {
        Files.writeString(output, "existing");
        assertEquals(1, run(args("csv")));
        assertEquals("existing", Files.readString(output));
        Files.delete(output);
        Files.createLink(output, input);
        String original = Files.readString(input);
        assertEquals(1, run(args("csv")));
        assertEquals(original, Files.readString(input));
        output = input;
        assertEquals(1, run(args("csv")));
        assertEquals(original, Files.readString(input));
    }

    @Test
    void shouldDiscardPartialOutputOnLateSchemaFailure() throws Exception {
        Files.writeString(input, "email,notes\nprivate@example.test,raw\nwrong-width\n");
        assertEquals(1, run(args("csv")));
        assertFalse(Files.exists(output));
        assertFalse(stderr.toString(StandardCharsets.UTF_8).contains("private@example.test"));
        assertNoStagingFiles();
    }

    @Test
    void shouldRejectSymlinkInputsAndOutputs() throws Exception {
        Path original = input;
        input = directory.resolve("link.csv");
        Files.createSymbolicLink(input, original);
        assertEquals(1, run(args("csv")));
        assertFalse(Files.exists(output));
        input = original;
        Files.createSymbolicLink(output, directory.resolve("missing.csv"));
        assertEquals(1, run(args("csv")));
        assertTrue(Files.isSymbolicLink(output));
    }

    @Test
    void shouldRejectNonPrivateOutputDirectory() throws Exception {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-x---"));
        assertEquals(1, run(args("csv")));
        assertFalse(Files.exists(output));
    }

    @Test
    void shouldReadRawKeyAndRejectUnsafeOrMissingKeys() throws Exception {
        Files.writeString(policy, "fields:\n  email:\n    strategy: hmac\n    scope: qa-email\n  notes:\n    strategy: drop\n");
        Path key = directory.resolve("key");
        Files.write(key, new byte[32]);
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(1, run(args("csv")));
        assertEquals(1, run(args("csv", "--key-file", key.toString())));
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        assertEquals(0, run(args("csv", "--key-file", key.toString())));
        assertTrue(Files.readString(output).matches("email\\r\\ndm1_[A-Za-z0-9_-]{43}\\r\\n"));
        Files.delete(output);
        Files.write(key, new byte[33]);
        assertEquals(1, run(args("csv", "--key-file", key.toString())));
        assertFalse(Files.exists(output));
    }

    @Test
    void shouldRejectMalformedUtf8AndCleanStaging() throws Exception {
        Files.write(input, new byte[]{(byte) 0xc3, 0x28});
        assertEquals(1, run(args("csv")));
        assertFalse(Files.exists(output));
        assertNoStagingFiles();
    }

    @Test
    void shouldRejectUnknownDuplicateOptionsAndAvoidEchoingArguments() {
        assertEquals(2, run(args("csv", "--format", "csv")));
        assertEquals(2, run(args("csv", "--secret", "private-value")));
        assertFalse(stderr.toString(StandardCharsets.UTF_8).contains("private-value"));
        assertEquals(0, run("--help"));
    }

    private void assertNoStagingFiles() throws Exception {
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith(".datamask4j-")));
        }
    }
}
