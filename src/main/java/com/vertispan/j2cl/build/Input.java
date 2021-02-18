package com.vertispan.j2cl.build;

import io.methvin.watcher.hashing.FileHash;
import io.methvin.watcher.hashing.Murmur3F;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.Checksum;

/**
 * New instances of this are made at every chance, and equality is only based on the
 * project and output type, so this can serve as a key when looking up work to do.
 *
 * Each instance can be updated to point at a specific disk cache entry where its
 * contents live, so it can be filtered if desired. The files in the contents
 * are already hashed, and each Input instance will filter to just the files it is
 * interested in, and take the hash of the hashes to represent
 */
public class Input {
    private static final PathMatcher[] EMPTY_PATH_MATCHER_ARRAY = new PathMatcher[0];
    private final Project project;
    private final String outputType;
    private final PathMatcher[] filters;

    private TaskOutput contents;

    public Input(Project project, String outputType) {
        this(project, outputType, EMPTY_PATH_MATCHER_ARRAY);
    }
    public Input(Project project, String outputType, PathMatcher[] filters) {
        this.project = project;
        this.outputType = outputType;
        this.filters = filters;
    }

    /**
     * Public API for tasks, builder to limit input scope.
     *
     * Specifies that only part of this input is required. A path entry that matches any
     * of the provided filters will be included.
     */
    public Input filter(PathMatcher... filters) {
        if (this.filters.length == 0) {
            return new Input(project, outputType, filters);
        }
        // we don't especially care if we get duplicates, this should be a short list, but
        // it would be nice to filter obvious dups, and the naive code here needs a copy
        // anyway.
        HashSet<PathMatcher> allMatchers = new HashSet<>(Arrays.asList(this.filters));
        allMatchers.addAll(Arrays.asList(filters));
        return new Input(project, outputType, allMatchers.toArray(filters));
    }

    /**
     * Internal API
     * Before a task is invoked we must assign contents to each input, and work out
     * the expected hash for the task, so we know where to put its outputs
     */
    public void setCurrentContents(TaskOutput contents) {
        this.contents = contents;
    }

    /**
     * Internal API
     * Updates the given hash object with the filtered file inputs - their paths and their
     * hashes, so that if files are moved or changed we change the hash value, but we don't
     * re-hash each file every time we ask.
     */
    public void updateHash(Murmur3F hash) {
        for (Map.Entry<Path, FileHash> fileAndHash : getFilesAndHashes().entrySet()) {
            hash.update(fileAndHash.getKey().toString().getBytes(StandardCharsets.UTF_8));
            hash.update(fileAndHash.getValue().asBytes());
        }
    }

    /**
     * Internal API
     */
    public Project getProject() {
        return project;
    }

    /**
     * Internal API
     */
    public String getOutputType() {
        return outputType;
    }

    /**
     * Public API for tasks.
     * Provides the whole directory - avoid this if you are using filters, as your task will not
     * get called again for changed files.
     */
    public Path getPath() {
        return contents.getPath();
    }

    /**
     * Public API for tasks.
     * Gets the current files of this input and their hashes that match the filters.
     */
    public Map<Path, FileHash> getFilesAndHashes() {
        return contents.filesAndHashes().stream()
                .filter(entry -> Arrays.stream(filters).anyMatch(f -> f.matches(entry.getKey())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Input input = (Input) o;

        if (!project.equals(input.project)) return false;
        return outputType.equals(input.outputType);
    }

    @Override
    public int hashCode() {
        int result = project.hashCode();
        result = 31 * result + outputType.hashCode();
        return result;
    }
}
