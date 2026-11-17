package io.github.hador.datamask4j.cli;

import io.github.hador.datamask4j.core.DataMasker;
import io.github.hador.datamask4j.file.CsvMasker;
import io.github.hador.datamask4j.file.JsonMasker;
import io.github.hador.datamask4j.file.YamlPolicyLoader;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Offline CLI for explicit UTF-8 CSV/JSON masking. No overwrite or stdout data output. */
public final class DataMaskCli {
    private static final String USAGE = """
            Usage: java -jar datamask4j-cli.jar mask --format csv|json
              --input FILE --output NEW_FILE --policy YAML [--key-file FILE]
            Output directory must be private (0700), POSIX, with hard-link support.
            Optional key file: exactly 32 raw random bytes, owner-only permissions.
            """;
    private static final Set<String> OPTIONS = Set.of("--format", "--input", "--output", "--policy", "--key-file");
    private static final Set<String> REQUIRED = Set.of("--format", "--input", "--output", "--policy");

    private DataMaskCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && "--help".equals(args[0])) {
            out.print(USAGE);
            return 0;
        }
        Map<String, String> options;
        try {
            options = parse(args);
        } catch (RuntimeException ignored) {
            err.println("Invalid arguments.");
            err.print(USAGE);
            return 2;
        }
        try {
            long count = mask(options, err);
            out.println("Masked records: " + count);
            return 0;
        } catch (IOException | RuntimeException ignored) {
            err.println("Masking failed. Check inputs, policy, permissions and destination.");
            return 1;
        }
    }

    private static Map<String, String> parse(String[] args) {
        require(args.length >= 1 && "mask".equals(args[0]) && args.length % 2 == 1);
        var result = new HashMap<String, String>();
        for (int index = 1; index < args.length; index += 2) {
            require(OPTIONS.contains(args[index]) && !args[index + 1].isBlank()
                    && !args[index + 1].startsWith("--"));
            require(result.putIfAbsent(args[index], args[index + 1]) == null);
        }
        require(result.keySet().containsAll(REQUIRED));
        require(Set.of("csv", "json").contains(result.get("--format")));
        return result;
    }

    private static long mask(Map<String, String> options, PrintStream err) throws IOException {
        Path source = regular(options.get("--input"));
        Path policy = regular(options.get("--policy"));
        Path output = Path.of(options.get("--output")).toAbsolutePath().normalize();
        Path parent = output.getParent();
        require(parent != null && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS));
        require(Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS)
                .equals(PosixFilePermissions.fromString("rwx------")));
        // A hard link publishes an already closed staging file, atomically and without replacing.
        require(Files.notExists(output, LinkOption.NOFOLLOW_LINKS));
        byte[] key = null;
        Path staged = null;
        try {
            if (options.containsKey("--key-file")) {
                Path keyFile = regular(options.get("--key-file"));
                require(Files.getPosixFilePermissions(keyFile, LinkOption.NOFOLLOW_LINKS).stream()
                        .allMatch(permission -> permission.name().startsWith("OWNER_")));
                try (var in = Channels.newInputStream(Files.newByteChannel(keyFile,
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
                    key = in.readNBytes(33);
                }
                require(key.length == 32);
            }
            DataMasker masker;
            try (Reader reader = reader(policy)) {
                masker = new DataMasker(new YamlPolicyLoader().load(reader, key));
            }
            staged = Files.createTempFile(parent, ".datamask4j-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            long count;
            try (Reader in = reader(source);
                 Writer writer = Channels.newWriter(Files.newByteChannel(staged,
                         Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)),
                         StandardCharsets.UTF_8.newEncoder(), -1)) {
                count = "csv".equals(options.get("--format"))
                        ? new CsvMasker(masker).mask(in, writer)
                        : new JsonMasker(masker).mask(in, writer);
            }
            Files.createLink(output, staged);
            return count;
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException | RuntimeException ignored) {
                    err.println("Warning: private staging file cleanup failed; inspect output directory.");
                }
            }
        }
    }

    private static Path regular(String name) {
        Path path = Path.of(name).toAbsolutePath().normalize();
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        return path;
    }

    private static Reader reader(Path path) throws IOException {
        return Channels.newReader(Files.newByteChannel(path,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)),
                StandardCharsets.UTF_8.newDecoder(), -1);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Unsupported configuration");
        }
    }
}
