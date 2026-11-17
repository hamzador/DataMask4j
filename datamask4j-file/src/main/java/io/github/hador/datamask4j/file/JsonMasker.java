package io.github.hador.datamask4j.file;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteFeature;
import io.github.hador.datamask4j.core.DataMasker;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Streams a single JSON array of flat string/null objects. Discard output on any error. */
public final class JsonMasker {
    private final DataMasker masker;
    private final FileMaskingLimits limits;
    private final JsonFactory factory;

    public JsonMasker(DataMasker masker) {
        this(masker, FileMaskingLimits.defaults());
    }

    public JsonMasker(DataMasker masker, FileMaskingLimits limits) {
        this.masker = Objects.requireNonNull(masker, "masker");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .disable(StreamWriteFeature.AUTO_CLOSE_CONTENT)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(2)
                        .maxStringLength(limits.maxValueChars())
                        .maxNameLength(limits.maxValueChars())
                        .build())
                .build();
    }

    /** Returns masked object count; caller owns both streams. */
    public long mask(Reader source, Writer destination) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        var boundedInput = BoundedStreams.reader(source, limits.maxInputChars());
        var boundedOutput = BoundedStreams.writer(destination, limits.maxOutputChars());
        try (var parser = factory.createParser(boundedInput);
             var generator = factory.createGenerator(boundedOutput)) {
            require(parser.nextToken() == JsonToken.START_ARRAY);
            generator.writeStartArray();
            long count = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                MaskingChecks.recordCount(count, limits);
                require(parser.currentToken() == JsonToken.START_OBJECT);
                var record = readRecord(parser);
                var masked = masker.mask(record);
                MaskingChecks.record(masked, limits);
                writeRecord(generator, masked);
                count++;
            }
            require(parser.nextToken() == null);
            generator.writeEndArray();
            generator.flush();
            return count;
        } catch (IOException | RuntimeException ignored) {
            throw new FileMaskingException("JSON masking failed; discard partial output");
        }
    }

    private Map<String, String> readRecord(JsonParser parser) throws IOException {
        var record = new LinkedHashMap<String, String>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.FIELD_NAME);
            String name = parser.currentName();
            MaskingChecks.value(name, limits);
            MaskingChecks.fields(record.size() + 1, limits);
            require(!record.containsKey(name));
            JsonToken token = parser.nextToken();
            require(token == JsonToken.VALUE_STRING || token == JsonToken.VALUE_NULL);
            String value = token == JsonToken.VALUE_NULL ? null : parser.getText();
            MaskingChecks.value(value, limits);
            record.put(name, value);
        }
        return record;
    }

    private static void writeRecord(JsonGenerator generator, Map<String, String> record) throws IOException {
        generator.writeStartObject();
        for (var field : record.entrySet()) {
            generator.writeFieldName(field.getKey());
            if (field.getValue() == null) {
                generator.writeNull();
            } else {
                generator.writeString(field.getValue());
            }
        }
        generator.writeEndObject();
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new FileMaskingException("Unsupported JSON structure");
        }
    }
}
