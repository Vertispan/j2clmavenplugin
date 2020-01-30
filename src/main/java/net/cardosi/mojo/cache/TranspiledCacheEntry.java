package net.cardosi.mojo.cache;

import net.cardosi.mojo.ClosureBuildConfiguration;

import java.io.File;

public class TranspiledCacheEntry {
    private final String hash;
    private final String artifactId;
    private final File cacheDir;

    public TranspiledCacheEntry(String hash, String artifactId, File cacheDir) {
        this.hash = hash;
        this.artifactId = artifactId;
        this.cacheDir = cacheDir;
    }

    public String getHash() {
        return hash;
    }

    @Override
    public String toString() {
        return "TranspiledCacheEntry{" +
                "hash='" + hash + '\'' +
                ", artifactId='" + artifactId + '\'' +
                '}';
    }

    public String getDirName() {
        return hash + "-" + artifactId;
    }

    public File getCacheDir() {
        return cacheDir;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public File getAnnotationSourcesDir() {
        return dir("annotation-sources");
    }

    private File dir(String name) {
        File dir = new File(cacheDir, name);
        dir.mkdir();
        return dir;
    }

    private File file(String name) {
        return new File(cacheDir, name);
    }

    public File getBytecodeDir() {
        return dir("bytecode");
    }

    public File getStrippedSourcesDir() {
        return dir("stripped-sources");
    }

    public File getUnpackedSources() {
        return dir("unpacked-sources");
    }

    public File getStrippedBytecodeDir() {
        return dir("stripped-bytecode");
    }

    public File getTranspiledSourcesDir() {
        return dir("transpiled-sources");
    }

    public File getExternsFile() {
        return file("externs.js");
    }

    public File getClosureOutputDir(ClosureBuildConfiguration config) {
        return dir("closure-output-" + config.hash());
    }

    public File getProjBundleDir() {
        return dir("proj-bundle");
    }
}
