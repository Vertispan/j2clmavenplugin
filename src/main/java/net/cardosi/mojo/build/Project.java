package net.cardosi.mojo.build;

import java.util.List;

/**
 * Represents a set of sources and the dependencies used to build them. The sourceRoots property
 * can contain directories of sources, or a jar containing sources, but not both - if it doesn't
 * point at a source jar, the contents can be watched for changes.
 */
public class Project {
    private String key;

    private List<Dependency> dependencies;
    private List<String> sourceRoots;

    public List<Dependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<Dependency> dependencies) {
        this.dependencies = dependencies;
    }

    public List<String> getSourceRoots() {
        return sourceRoots;
    }

    public void setSourceRoots(List<String> sourceRoots) {
        this.sourceRoots = sourceRoots;
    }

    public boolean hasSourcesMapped() {
        return sourceRoots.stream().noneMatch(root -> root.endsWith(".jar"));
    }
}
