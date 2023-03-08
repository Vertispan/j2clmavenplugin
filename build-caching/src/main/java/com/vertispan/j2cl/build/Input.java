package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.ChangedCachedPath;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.stream.Collectors;

/**
 * New instances of this are made at every change, and equality is only based on the
 * project and output type, so this can serve as a key when looking up work to do.
 *
 * Each instance can be updated to point at a specific disk cache entry where its
 * contents live, so it can be filtered if desired. The files in the contents
 * are already hashed, and each Input instance will filter to just the files it is
 * interested in, and take the hash of the hashes to represent
 */
public class Input implements com.vertispan.j2cl.build.task.Input {
    public interface BuildSpecificChanges {
        List<? extends ChangedCachedPath> compute();
        static BuildSpecificChanges memoize(BuildSpecificChanges changes) {
            return new BuildSpecificChanges() {
                private List<ChangedCachedPath> results;
                @Override
                public List<ChangedCachedPath> compute() {
                    if (results == null) {
                        List<? extends ChangedCachedPath> computed = changes.compute();
                        assert computed != null;
                        results = new ArrayList<>(computed);
                    }

                    return results;
                }
            };
        }
    }
    private final Project project;
    private final String outputType;

    private TaskOutput contents;
    private BuildSpecificChanges buildSpecificChanges;

    public Input(Project project, String outputType) {
        this.project = project;
        this.outputType = outputType;
    }

    public boolean hasContents() {
        return contents != null;
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
        public Collection<Path> getParentPaths() {
            return getFilesAndHashes().stream().map(DiskCache.CacheEntry::getAbsoluteParent).collect(Collectors.toSet());
        }

        @Override
        public Collection<DiskCache.CacheEntry> getFilesAndHashes() {
            return wrapped.getFilesAndHashes().stream()
                    .filter(entry -> Arrays.stream(filters).anyMatch(f -> f.matches(entry.getSourcePath())))
                    .collect(Collectors.toUnmodifiableList());
        }

        @Override
        public Collection<? extends ChangedCachedPath> getChanges() {
            return wrapped.getChanges().stream()
                    .filter(entry -> Arrays.stream(filters).anyMatch(f -> f.matches(entry.getSourcePath())))
                    .collect(Collectors.toUnmodifiableList());
        }

        @Override
        public com.vertispan.j2cl.build.task.Project getProject() {
            return wrapped.getProject();
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
     * Once a task is finished, we can let it be used by other tasks as an input. In
     * order for those tasks to execute incrementally, each particular Input must
     * only have changed relative to the last time that its owner task ran successfully.
     * Instead of being set all at once, this is re-assigned before each task actually
     * executes.
     */
    public void setBuildSpecificChanges(BuildSpecificChanges buildSpecificChanges) {
        this.buildSpecificChanges = BuildSpecificChanges.memoize(buildSpecificChanges);
    }

    /**
     * Internal API.
     *
     * Creates a simple payload that describes this input, so it can be written to disk.
     */
    public TaskSummaryDiskFormat.InputDiskFormat makeDiskFormat() {
        TaskSummaryDiskFormat.InputDiskFormat out = new TaskSummaryDiskFormat.InputDiskFormat();

        out.setProjectKey(getProject().getKey());
        out.setOutputType(getOutputType());

        out.setFileHashes(getFilesAndHashes().stream().collect(Collectors.toMap(
                e -> e.getSourcePath().toString(),
                e -> e.getHash().asString()
        )));

        return out;
    }



    @Override
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
    public Collection<Path> getParentPaths() {
        return getFilesAndHashes().stream().map(DiskCache.CacheEntry::getAbsoluteParent).collect(Collectors.toSet());
    }

    @Override
    public Collection<DiskCache.CacheEntry> getFilesAndHashes() {
        if (contents == null) {
            throw new NullPointerException("Contents not yet provided " + this);
        }
        return contents.filesAndHashes();
    }

    @Override
    public Collection<? extends ChangedCachedPath> getChanges() {
        if (buildSpecificChanges == null) {
            throw new NullPointerException("Changes not yet provided " + this);
        }
        return buildSpecificChanges.compute();
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
