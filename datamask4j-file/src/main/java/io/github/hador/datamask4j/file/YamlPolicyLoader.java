package io.github.hador.datamask4j.file;

import io.github.hador.datamask4j.core.FieldRule;
import io.github.hador.datamask4j.core.MaskingPolicy;
import io.github.hador.datamask4j.crypto.HmacPseudonymizer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loads a restricted YAML policy AST. No arbitrary object deserialisation or inline key. */
public final class YamlPolicyLoader {
    private static final int MAX_POLICY_CHARS = 262_144;
    private static final int MAX_FIELDS = 128;
    private static final int MAX_SCALAR_CHARS = 65_536;

    public MaskingPolicy load(Reader source) {
        return load(source, null);
    }

    /** Key is supplied separately, copied by each HMAC strategy, and remains caller-owned. */
    public MaskingPolicy load(Reader source, byte[] hmacKey) {
        Objects.requireNonNull(source, "source");
        try {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(8);
            options.setCodePointLimit(MAX_POLICY_CHARS);
            var yaml = new Yaml(new SafeConstructor(options));
            Node root = yaml.compose(BoundedStreams.reader(source, MAX_POLICY_CHARS));
            var top = mapping(root, 1);
            require(top.keySet().equals(Set.of("fields")));
            var fields = mapping(top.get("fields"), MAX_FIELDS);
            var builder = MaskingPolicy.builder();
            for (var entry : fields.entrySet()) {
                builder.field(entry.getKey(), rule(mapping(entry.getValue(), 2), hmacKey));
            }
            return builder.build();
        } catch (RuntimeException ignored) {
            throw new FileMaskingException("Invalid YAML masking policy");
        }
    }

    private static FieldRule rule(Map<String, Node> config, byte[] key) {
        String strategy = string(config.get("strategy"));
        return switch (strategy) {
            case "keep" -> { exact(config, "strategy"); yield FieldRule.keep(); }
            case "drop" -> { exact(config, "strategy"); yield FieldRule.drop(); }
            case "redact" -> { exact(config, "strategy"); yield FieldRule.redact(); }
            case "constant" -> {
                exact(config, "strategy", "value");
                yield FieldRule.constant(string(config.get("value")));
            }
            case "hmac" -> {
                exact(config, "strategy", "scope");
                require(key != null);
                yield FieldRule.transform(new HmacPseudonymizer(key, string(config.get("scope"))));
            }
            default -> throw new FileMaskingException("Unsupported strategy");
        };
    }

    private static Map<String, Node> mapping(Node node, int limit) {
        require(node instanceof MappingNode && Tag.MAP.equals(node.getTag()) && node.getAnchor() == null);
        var tuples = ((MappingNode) node).getValue();
        require(tuples.size() <= limit);
        var result = new LinkedHashMap<String, Node>();
        for (var tuple : tuples) {
            String name = string(tuple.getKeyNode());
            require(!result.containsKey(name));
            result.put(name, tuple.getValueNode());
        }
        return result;
    }

    private static String string(Node node) {
        require(node instanceof ScalarNode && Tag.STR.equals(node.getTag()) && node.getAnchor() == null);
        String value = ((ScalarNode) node).getValue();
        require(value.length() <= MAX_SCALAR_CHARS);
        return value;
    }

    private static void exact(Map<String, Node> config, String... fields) {
        require(config.keySet().equals(Set.of(fields)));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new FileMaskingException("Unsupported policy structure");
        }
    }
}
