package net.cardosi.mojo.cache;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TranspiledCacheEntry {
//    private String key;
    private final String hash;
    private final String artifactId;

    public TranspiledCacheEntry(String hash, String artifactId) {
        this.hash = hash;
        this.artifactId = artifactId;
    }

    //    private List<TranspiledCacheEntry> dependencies;
//    private File strippedBytecode;
//    private File jsZip;
//
//    private final List<TranspiledCacheEntry> downstream = new ArrayList<>();
//
//    public void transpile(MavenProject currentProject, DiskCache diskCache) {
//        key = ArtifactUtils.key(currentProject.getArtifactId(), currentProject.getGroupId(), currentProject.getVersion());
////        System.out.println("TODO transpile " + currentProject);
//
//
//    }
//
////    public boolean matches(Artifact artifact) {
////        return true;
////    }
//
    public String getHash() {
        return hash;
    }
//
//    public void setHash(String hash) {
//        this.hash = hash;
//    }
//
//    public List<TranspiledCacheEntry> getDependencies() {
//        return dependencies;
//    }
//
//    public void setDependencies(List<TranspiledCacheEntry> dependencies) {
//        assert this.dependencies == null && dependencies != null;
//        this.dependencies = dependencies;
//        for (TranspiledCacheEntry dependency : dependencies) {
//            dependency.downstream.add(this);
//        }
//    }
//
//    public File getStrippedBytecode() {
//        return strippedBytecode;
//    }
//
//    public File getJsZip() {
//        return jsZip;
//    }
//
//    public String getKey() {
//        return key;
//    }

    @Override
    public String toString() {
        return "TranspiledCacheEntry{" +
                ", hash='" + hash + '\'' +
                '}';
    }

    public String getArtifactId() {
        return artifactId;
    }
}
