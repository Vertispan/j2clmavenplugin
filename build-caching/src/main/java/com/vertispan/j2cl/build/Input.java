package com.vertispan.j2cl.build;

import io.methvin.watcher.hashing.FileHash;
import io.methvin.watcher.hashing.Murmur3F;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * New instances of this are made at every chance, and equality is only based on the
 * project and output type, so this can serve as a key when looking up work to do.
 *
 * Each instance can be updated to point at a specific disk cache entry where its
 * contents live, so it can be filtered if desired. The files in the contents
 * are already hashed, and each Input instance will filter to just the files it is
 * interested in, and take the hash of the hashes to represent
 */
public class Input implements com.vertispan.j2cl.build.task.Input {
    private final Project project;
    private final String outputType;

    private TaskOutput contents;

    public Input(Project project, String outputType) {
        this.project = project;
        this.outputType = outputType;
    }

    /**
     * Filtered implementation, so that we don't have to track each instance floating around, just
     * the top level ones.
     */
    private static class FilteredInput implements com.vertispan.j2cl.build.task.Input {
        private final Input wrapped;
        private final PathMatcher[] filters;
        public FilteredInput(Input input, PathMatcher[] filters) {
            this.wrapped = input;
            this.filters = filters;
        }

        @Override
        public com.vertispan.j2cl.build.task.Input filter(PathMatcher... filters) {
            // we don't especially care if we get duplicates, this should be a short list, but
            // it would be nice to filter obvious dups, and the naive code here needs a copy
            // anyway.
            HashSet<PathMatcher> allMatchers = new HashSet<>(Arrays.asList(this.filters));
            allMatchers.addAll(Arrays.asList(filters));
            return new FilteredInput(wrapped, allMatchers.toArray(filters));
        }

        @Override
        public Path getPath() {
            return wrapped.getPath();
        }

        @Override
        public Map<Path, FileHash> getFilesAndHashes() {
            return wrapped.contents.filesAndHashes().stream()
                    .filter(entry -> Arrays.stream(filters).anyMatch(f -> f.matches(entry.getKey())))
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (h1, h2) -> {throw new IllegalStateException("Can't have two files with the same path");},
                            TreeMap::new
                    ));
        }

        @Override
        public String toString() {
            return "FilteredInput{" +
                    "wrapped=" + wrapped +
                    ", filters=" + Arrays.toString(filters) +
                    '}';
        }
    }

    @Override
    public com.vertispan.j2cl.build.task.Input filter(PathMatcher... filters) {
        if (filters.length == 0) {
            return this;
        }
        return new FilteredInput(this, filters);
    }

    /**
     * Internal API.
     *
     * Before a task is invoked we must assign contents to each input, and work out
     * the expected hash for the task, so we know where to put its outputs. This is
     * called by the DiskCache or TaskScheduler as they accumulate the output from
     * a task.
     */
    public void setCurrentContents(TaskOutput contents) {
        this.contents = contents;
    }

    /**
     * Internal API.
     *
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
     * Internal API.
     */
    public Project getProject() {
        return project;
    }

    /**
     * Internal API.
     */
    public String getOutputType() {
        return outputType;
    }

    @Override
    public Path getPath() {
        return contents.getPath();
    }

    @Override
    public Map<Path, FileHash> getFilesAndHashes() {
        return contents.filesAndHashes().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (h1, h2) -> {throw new IllegalStateException("Can't have two files with the same path");},
                        TreeMap::new
                ));
    }

    @Override
    public String toString() {
        return "Input{" +
                "project=" + project +
                ", outputType='" + outputType + '\'' +
                ", contents=" + contents +
                '}';
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
