package com.vertispan.j2cl.build;

import java.util.List;

/**
 * Represents a set of sources and the dependencies used to build them. The sourceRoots property
 * can contain directories of sources, or a jar containing sources, but not both - if it doesn't
 * point at a source jar, the contents can be watched for changes.
 */
public class Project implements com.vertispan.j2cl.build.task.Project {
    private final String key;

    private List<? extends com.vertispan.j2cl.build.task.Dependency> dependencies;
    private List<String> sourceRoots;

    private boolean isJsZip = false;

    public Project(String key) {
        this.key = key;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public List<? extends com.vertispan.j2cl.build.task.Dependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<? extends com.vertispan.j2cl.build.task.Dependency> dependencies) {
        this.dependencies = dependencies;
    }

    public List<String> getSourceRoots() {
        return sourceRoots;
    }

    public void setSourceRoots(List<String> sourceRoots) {
        this.sourceRoots = sourceRoots;
    }

    @Override
    public boolean hasSourcesMapped() {
        return sourceRoots.stream().noneMatch(root -> root.endsWith(".jar") || root.endsWith(".zip"));
    }

    @Override
    public String toString() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Project project = (Project) o;

        return key.equals(project.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    /**
     * Indicate that this Project is actually just a jszip, so should only be unpacked before having
     * closure run on it, but should not actually run javac or j2cl on it.
     *
     * This is an ugly workaround for using bazel outputs in a maven build. That is, we must rely on
     * bazel to build these jars, since the upstream google/j2cl project could change how this is
     * built, and we need to rely on that output being accurate and consistent across versions.
     */
    public void markJsZip() {
        isJsZip = true;
    }

    @Override
    public boolean isJsZip() {
        return isJsZip;
    }
}
