package net.neoforged.neoform.runtime.cache;

import net.neoforged.neoform.runtime.graph.ExecutionNode;
import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.FileUtil;
import net.neoforged.neoform.runtime.utils.StringUtils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cache storage is generally handled as follows:
 * <p/>
 * <ul>
 * <li>Artifacts are stored independently of the Minecraft version we work with, since required libraries
 *   have a high chance of being used across multiple versions.</li>
 * <li>While it is unlikely that intermediate results will be shared across Minecraft versions they are stored using
 * hash-keys. As such, the cleanup routine will take care of removing old Minecraft version intermediate results over time.</li>
 * <li>Game asset indices are shared across Minecraft versions since many assets do not change between versions.</li>
 * </ul>
 */
public class CacheManager implements AutoCloseable {
    private final Path homeDir;
    private final Path artifactCacheDir;
    private final Path intermediateResultsDir;
    private final Path assetsDir;

    /**
     * Maximum age of cache entries in the intermediate work cache in hours.
     */
    private long maxAgeInHours = 24 * 31;
    /**
     * Maximum overall size of the intermediate work cache.
     */
    private long maxSize = 1024 * 1024 * 1024;

    private boolean disabled;
    private boolean analyzeMisses;
    private boolean verbose;

    public CacheManager(Path homeDir) throws IOException {
        this.homeDir = homeDir;
        Files.createDirectories(homeDir);
        this.artifactCacheDir = homeDir.resolve("artifacts");
        this.intermediateResultsDir = homeDir.resolve("intermediate_results");
        this.assetsDir = homeDir.resolve("assets");
    }

    public void performMaintenance() throws IOException {
        var cacheLock = homeDir.resolve("nfrt_cache_cleanup.state");

        try (var channel = FileChannel.open(cacheLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException ignored) {
                lock = null;
            }

            if (lock != null) {
                var lastModified = Files.getLastModifiedTime(cacheLock, LinkOption.NOFOLLOW_LINKS);
                var age = Duration.between(lastModified.toInstant(), Instant.now());
                var interval = Duration.ofHours(24);
                if (age.compareTo(interval) < 0) {
                    if (verbose) {
                        System.out.println("Not performing routine maintenance since the last maintenance was "
                                           + AnsiColor.BLACK_BOLD + StringUtils.formatDuration(age) + " ago" + AnsiColor.BLACK_BOLD);
                    }
                    return;
                }

                System.out.println("Performing periodic cache maintenance on " + homeDir);

                cleanUpIntermediateResults();

                Files.setLastModifiedTime(cacheLock, FileTime.from(Instant.now()));

                return;
            }

            System.out.println("Cache maintenance is already performed by another process.");
        }
    }

    public void cleanUpAll() throws IOException {
        cleanUpIntermediateResults();
    }

    /**
     * Cleans the cache of intermediate results based on two goals:
     * <ol>
     * <li>Removing cache entries that have not been used for a given number of hours. We use the last modification
     * time of the cache key files as the indicator for their last use.</li>
     * <li>Removing enough cache entries to keep the overall size under the given target.</li>
     * </ul>
     */
    public void cleanUpIntermediateResults() throws IOException {
        System.out.println("Cleaning intermediate results cache in " + intermediateResultsDir);
        System.out.println(" Maximum age: " + maxAgeInHours + "h");
        System.out.println(" Maximum cache size: " + StringUtils.formatBytes(maxSize));

        record CacheEntry(Path file, String filename, String cacheKey, long lastModified, long size) {
        }
        var entries = new ArrayList<CacheEntry>(1000);
        var expiredEntryPrefixes = new HashSet<String>();

        var now = Instant.now();
        Files.walkFileTree(intermediateResultsDir, Set.of(), 1, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    // Find expired cache control files since those are the files that get touched
                    // when used.
                    var filename = file.getFileName().toString();
                    var m = CacheKey.FILENAME_PREFIX_PATTERN.matcher(filename);
                    if (m.find()) {
                        String cacheKey = m.group(1);
                        entries.add(new CacheEntry(file, filename, cacheKey, attrs.lastModifiedTime().toMillis(), attrs.size()));

                        if (filename.substring(cacheKey.length()).equals(".txt")) {
                            long ageInHours = Duration.between(attrs.lastModifiedTime().toInstant(), now).toHours();
                            if (ageInHours > maxAgeInHours) {
                                expiredEntryPrefixes.add(cacheKey);
                            }
                        }
                    } else {
                        System.out.println("  Unrecognized file in cache: " + file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        var totalSize = entries.stream().mapToLong(CacheEntry::size).sum();

        System.out.println(" " + AnsiColor.BLACK_BRIGHT + entries.size() + " files found" + AnsiColor.RESET);
        System.out.println(" " + AnsiColor.BLACK_BRIGHT + StringUtils.formatBytes(totalSize) + " overall size" + AnsiColor.RESET);
        System.out.println(" " + AnsiColor.BLACK_BRIGHT + expiredEntryPrefixes.size() + " expired keys found" + AnsiColor.RESET);

        // Nothing to expire
        if (!expiredEntryPrefixes.isEmpty()) {
            long freedSpace = 0;
            long deletedEntries = 0;

            // Delete all entries that belong to expired cache keys
            for (var it = entries.iterator(); it.hasNext(); ) {
                var item = it.next();
                if (expiredEntryPrefixes.contains(item.cacheKey)) {
                    if (verbose) {
                        System.out.println(" Deleting " + item.filename);
                    }
                    try {
                        Files.delete(item.file);
                    } catch (IOException e) {
                        System.err.println("Failed to delete cache entry " + item.file);
                        continue;
                    }
                    freedSpace += item.size;
                    deletedEntries++;
                    it.remove();
                }
            }

            System.out.println("Freed up " + AnsiColor.BLACK_BOLD + StringUtils.formatBytes(freedSpace) + AnsiColor.RESET + " by deleting " + AnsiColor.BLACK_BOLD + deletedEntries + " expired entries" + AnsiColor.RESET);
            totalSize -= freedSpace;
        }

        if (totalSize <= maxSize) {
            return;
        }

        System.out.println("Cache size exceeds target size. Deleting oldest entries first.");

        // If the total size still exceeds the target, group remaining cache entries by key and find the biggest impact
        var groupedEntries = new ArrayList<>(entries.stream().collect(Collectors.groupingBy(CacheEntry::cacheKey)).values());
        groupedEntries.sort(Comparator.<List<CacheEntry>>comparingLong(group -> group.stream().mapToLong(CacheEntry::size).sum()).reversed());
        long freedSpace = 0;
        var deletedEntries = 0;
        for (var group : groupedEntries) {
            if (totalSize <= maxSize) {
                break;
            }

            for (var item : group) {
                if (verbose) {
                    System.out.println(" Deleting " + item.filename);
                }
                try {
                    Files.delete(item.file);
                } catch (IOException e) {
                    System.err.println("Failed to delete cache entry " + item.file);
                    continue;
                }
                freedSpace += item.size;
                totalSize -= item.size;
                deletedEntries++;
            }
        }

        System.out.println("Freed up " + AnsiColor.BLACK_BOLD + StringUtils.formatBytes(freedSpace) + AnsiColor.RESET + " by deleting " + AnsiColor.BLACK_BOLD + deletedEntries + " entries" + AnsiColor.RESET);
    }

    public boolean restoreOutputsFromCache(ExecutionNode node, CacheKey cacheKey, Map<String, Path> outputValues) throws IOException {
        var intermediateCacheDir = getIntermediateResultsDir();
        var cacheMarkerFile = getCacheMarkerFile(cacheKey);
        Files.createDirectories(intermediateCacheDir);
        if (Files.isRegularFile(cacheMarkerFile)) {
            // Try to rebuild output values from cache
            boolean complete = true;
            for (var entry : node.outputs().entrySet()) {
                var filename = cacheKey + "_" + entry.getKey() + node.getRequiredOutput(entry.getKey()).type().getExtension();
                var cachedFile = intermediateCacheDir.resolve(filename);
                if (Files.isRegularFile(cachedFile)) {
                    outputValues.put(entry.getKey(), cachedFile);
                } else {
                    System.err.println("Cache for " + node.id() + " is incomplete. Missing: " + filename);
                    outputValues.clear();
                    complete = false;
                    break;
                }
            }
            if (complete) {
                // Mark its use
                Files.setLastModifiedTime(cacheMarkerFile, FileTime.from(Instant.now()));
            }
            return complete;
        } else if (analyzeMisses) {
            analyzeCacheMiss(cacheKey);
        }
        return false;
    }

    public void saveOutputs(ExecutionNode node, CacheKey cacheKey, HashMap<String, Path> outputValues) throws IOException {
        var intermediateCacheDir = getIntermediateResultsDir();
        var finalOutputValues = new HashMap<String, Path>(outputValues.size());
        for (var entry : outputValues.entrySet()) {
            var filename = cacheKey + "_" + entry.getKey() + node.getRequiredOutput(entry.getKey()).type().getExtension();
            var cachedPath = intermediateCacheDir.resolve(filename);
            FileUtil.atomicMove(entry.getValue(), cachedPath);
            finalOutputValues.put(entry.getKey(), cachedPath);
        }
        outputValues.putAll(finalOutputValues);
        cacheKey.write(getCacheMarkerFile(cacheKey));
    }

    private Path getCacheMarkerFile(CacheKey cacheKey) {
        return getIntermediateResultsDir().resolve(cacheKey.type() + "_" + cacheKey.hashValue() + ".txt");
    }

    private Path getIntermediateResultsDir() {
        return intermediateResultsDir;
    }

    public Path getArtifactCacheDir() {
        return artifactCacheDir;
    }

    public Path getAssetsDir() {
        return assetsDir;
    }

    record CacheEntry(String filename, FileTime lastModified, CacheKey cacheKey) {
    }

    private void analyzeCacheMiss(CacheKey cacheKey) {
        var intermediateCacheDir = getIntermediateResultsDir();
        var cacheEntries = new ArrayList<>(getCacheEntries(intermediateCacheDir, cacheKey.type()));
        System.out.println("  " + cacheEntries.size() + " existing cache entries for " + cacheKey.type());

        // Calculate distances
        var deltasByCacheEntry = new IdentityHashMap<CacheEntry, List<CacheKey.Delta>>(cacheEntries.size());
        for (var cacheEntry : cacheEntries) {
            deltasByCacheEntry.put(cacheEntry, cacheKey.getDiff(cacheEntry.cacheKey()));
        }

        cacheEntries.sort(Comparator.comparingInt(value -> deltasByCacheEntry.get(value).size()));

        for (var cacheEntry : cacheEntries) {
            var diffCount = deltasByCacheEntry.get(cacheEntry).size();
            System.out.println("    " + cacheEntry.filename + " " + cacheEntry.lastModified + " " + diffCount + " deltas");
        }

        if (!cacheEntries.isEmpty()) {
            System.out.println("  Detailed delta for cache entry with best match:");
            for (var delta : deltasByCacheEntry.get(cacheEntries.getFirst())) {
                System.out.println("    " + AnsiColor.BLACK_UNDERLINED + delta.key() + AnsiColor.RESET);
                System.out.println(AnsiColor.BLACK_BRIGHT + "      New: " + AnsiColor.RESET + print(delta.ours()));
                System.out.println(AnsiColor.BLACK_BRIGHT + "      Old: " + AnsiColor.RESET + print(delta.theirs()));
            }
        }
    }

    private static String print(CacheKey.AnnotatedValue value) {
        if (value.annotation() != null) {
            return value.value() + AnsiColor.BLACK_BRIGHT + " (" + value.annotation() + ")" + AnsiColor.RESET;
        }
        return value.value();
    }

    private static List<CacheEntry> getCacheEntries(Path intermediateCacheDir, String type) {
        var filenamePattern = Pattern.compile(Pattern.quote(type) + "_[0-9a-f]+\\.txt");

        try (var stream = Files.list(intermediateCacheDir)) {
            return stream.filter(f -> filenamePattern.matcher(f.getFileName().toString()).matches()).map(p -> {
                try {
                    return new CacheEntry(p.getFileName().toString(), Files.getLastModifiedTime(p), CacheKey.read(p));
                } catch (Exception e) {
                    System.err.println("  Failed to read cache-key " + p + " for analysis");
                    return null;
                }
            }).filter(Objects::nonNull).toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isAnalyzeMisses() {
        return analyzeMisses;
    }

    public void setAnalyzeMisses(boolean analyzeMisses) {
        this.analyzeMisses = analyzeMisses;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public void close() throws Exception {
    }
}
