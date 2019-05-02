package net.cardosi.mojo.cache;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class CachedProject {
    private final DiskCache diskCache;
    private final Artifact artifact;
    private final MavenProject currentProject;
    private final List<CachedProject> children;

    private boolean dirty;

    private volatile CompletableFuture<TranspiledCacheEntry> compiledOutput;

    public CachedProject(DiskCache diskCache, Artifact artifact, MavenProject currentProject, List<CachedProject> children) {

        this.diskCache = diskCache;
        this.artifact = artifact;
        this.currentProject = currentProject;
        this.children = children;

        compiledOutput = new CompletableFuture<>();
    }

    public void markDirty() {
        if (dirty) {
            return;
        }
        dirty = true;

        children.forEach(CachedProject::markDirty);

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
                ", children=" + children +
                ", dirty=" + dirty +
                '}';
    }
}
