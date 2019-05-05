package net.cardosi.mojo.cache;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class CachedProject {
    private final DiskCache diskCache;
    private final Artifact artifact;
    private final MavenProject currentProject;
    private final List<CachedProject> children;
    private final List<CachedProject> dependents = new ArrayList<>();

    private boolean dirty;

    private volatile CompletableFuture<TranspiledCacheEntry> compiledOutput;

    private boolean ignoreJavacFailure;

    public CachedProject(DiskCache diskCache, Artifact artifact, MavenProject currentProject, List<CachedProject> children) {

        this.diskCache = diskCache;
        this.artifact = artifact;
        this.currentProject = currentProject;
        this.children = children;
        children.forEach(c -> c.dependents.add(this));

        compiledOutput = new CompletableFuture<>();
    }

    public void markDirty() {
        if (dirty) {
            return;
        }
        dirty = true;

        dependents.forEach(CachedProject::markDirty);

        compiledOutput = new CompletableFuture<>();
        compiledOutput.thenRun(() -> {
            dirty = false;
//            System.out.println("marked clean " + this);
        });

//        System.out.println("marked dirty " + this);
        diskCache.submit(this);
    }

    public boolean isDirty() {
        return dirty;
    }

    public List<CachedProject> getChildren() {
        return children;
    }

    public CompletableFuture<TranspiledCacheEntry> getCompiledOutput() {
        return compiledOutput;
    }

    public MavenProject getMavenProject() {
        return currentProject;
    }

    public String getArtifactId() {
        return artifact.getArtifactId();
    }

    public Artifact getArtifact() {
        return artifact;
    }

    @Override
    public String toString() {
        return "CachedProject{" +
                "artifact=" + artifact +
                ", currentProject=" + currentProject +
                ", children=" + children.stream().map(CachedProject::getArtifactId).collect(Collectors.joining(", ")) +
                ", dirty=" + dirty +
                '}';
    }

    public boolean hasSourcesMapped() {
        //TODO should eventually support external artifact source dirs so we can watch that instead of using jars
        return !getMavenProject().getCompileSourceRoots().isEmpty() && !getMavenProject().getTestCompileSourceRoots().isEmpty();
    }

    public void watch() {

    }

    public boolean isIgnoreJavacFailure() {
        return ignoreJavacFailure;
    }

    public void setIgnoreJavacFailure(boolean ignoreJavacFailure) {
        //TODO com.google.jsinterop:base, and make it configurable for other not-actually-compatible libs
        this.ignoreJavacFailure = ignoreJavacFailure;
    }
}
