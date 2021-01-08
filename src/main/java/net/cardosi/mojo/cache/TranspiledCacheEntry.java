package net.cardosi.mojo.cache;

import com.google.j2cl.transpiler.incremental.TypeInfo;
import net.cardosi.mojo.ClosureBuildConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TranspiledCacheEntry {
    private final String              hash;
    private final String              artifactId;
    private final File                cacheDir;
    private final List<TypeInfo>      impacting;
    private final Map<String, String> uniqueIdToPath;

    public TranspiledCacheEntry(String hash, String artifactId, File cacheDir) {
        this.hash = hash;
        this.artifactId = artifactId;
        this.cacheDir = cacheDir;
        this.impacting = new ArrayList<>();
        this.uniqueIdToPath = new HashMap<>();
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

    public File getClosureInputDir() {
        return dir("closure-inputs");
    }
    public File getClosureOutputDir(ClosureBuildConfiguration config) {
        return dir("closure-output-" + config.hash());
    }

    public File getProjBundleDir() {
        return dir("proj-bundle");
    }

    public List<TypeInfo> getImpacting() {
        return impacting;
    }

    public Map<String, String> getUniqueIdToPath() {
        return uniqueIdToPath;
    }
}
