package net.neoforged.neoform.runtime.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.neoform.runtime.utils.HashingUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public record CacheKey(
        String type,
        String hashValue,
        Map<String, AnnotatedValue> components
) {
    public static final String TYPE_REGEX = "[a-zA-Z0-9]{1,32}";
    public static final Pattern TYPE_PATTERN = Pattern.compile(TYPE_REGEX);
    public static final String HASH_VALUE_REGEX = "[0-9a-f]{40}";
    public static final Pattern HASH_VALUE_PATTERN = Pattern.compile(HASH_VALUE_REGEX);
    public static final Pattern FILENAME_PREFIX_PATTERN = Pattern.compile("^(" + TYPE_REGEX + "_" + HASH_VALUE_REGEX + ")");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public CacheKey {
        if (!TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException("Invalid type: " + type + ". Must conform to regular expression " + TYPE_REGEX);
        }
        if (!HASH_VALUE_PATTERN.matcher(hashValue).matches()) {
            throw new IllegalArgumentException("Invalid hash value: " + type + ". Must be a lower-case SHA1 hash");
        }
    }

    public CacheKey(String type, Map<String, AnnotatedValue> components) {
        this(type, computeHashValue(components), components);
    }

    public List<Delta> getDiff(CacheKey other) {

        var deltas = new ArrayList<Delta>();
        for (var entry : components.entrySet()) {
            var ourValue = entry.getValue();
            var theirValue = other.components().get(entry.getKey());

            if (theirValue == null || !ourValue.value().equals(theirValue.value())) {
                deltas.add(new Delta(entry.getKey(), ourValue, theirValue));
            }
        }
        for (var entry : other.components.entrySet()) {
            if (!components.containsKey(entry.getKey())) {
                deltas.add(new Delta(entry.getKey(), null, entry.getValue()));
            }
        }

        return deltas;
    }

    private static String computeHashValue(Map<String, AnnotatedValue> components) {
        var hashValue = components.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ": " + entry.getValue().value())
                .collect(Collectors.joining("\n"));
        return HashingUtil.sha1(hashValue);
    }

    public String describe() {
        var result = new StringBuilder();
        for (var entry : components.entrySet()) {
            result.append(entry.getKey()).append(": ").append(entry.getValue().value());
            if (entry.getValue().annotation != null) {
                result.append(" (").append(entry.getValue().annotation).append(")");
            }
            result.append("\n");
        }
        return result.toString();
    }

    public void write(Path path) throws IOException {
        Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    public static CacheKey read(Path path) throws IOException {
        return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), CacheKey.class);
    }

    @Override
    public String toString() {
        return type + "_" + hashValue;
    }

    /**
     * For some parts of cache-keys, it is beneficial to have additional information, such as filenames or paths,
     * which are not part of the cache key.
     */
    public record AnnotatedValue(String value, @Nullable String annotation) {
        @Override
        public String toString() {
            if (annotation == null) {
                return value;
            }
            return value + " (" + annotation + ")";
        }
    }

    public record Delta(String key, @Nullable AnnotatedValue ours, @Nullable AnnotatedValue theirs) {
    }
}
