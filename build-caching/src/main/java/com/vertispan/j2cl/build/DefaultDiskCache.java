package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import io.methvin.watcher.hashing.Murmur3F;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Default implementation of the disk cache, with one directory per project name, and
 * various builds of tasks within that. The build directory itself will be named for
 * the hash of the inputs the task needs - the task type and name, the files that are
 * read from other tasks, and the configuration options that the task is using.
 */
public class DefaultDiskCache extends DiskCache {
    /**
     * Constructs a new disk cache using the specified directory. It is assumed that
     * this path will be entirely owned by this library (even if shared across processes),
     * and that its entire contents will exist on the same file system.
      * @param cacheDir the directory in which to build the cache
     */
    public DefaultDiskCache(File cacheDir) throws IOException {
        super(cacheDir);
    }

    @Override
    protected Path taskDir(CollectedTaskInputs inputs) {
        String projectName = inputs.getProject().getKey();
        Murmur3F hash = new Murmur3F();

        hash.update(inputs.getTaskFactory().getClass().toString().getBytes(StandardCharsets.UTF_8));
        hash.update(inputs.getTaskFactory().getTaskName().getBytes(StandardCharsets.UTF_8));
        //TODO incorporate a version number into the task factory so we can differentiate between updates

        for (Input input : inputs.getInputs()) {
            input.updateHash(hash);
        }

        for (Map.Entry<String, String> entry : inputs.getUsedConfigs().entrySet()) {
            hash.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            hash.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
        }

        return cacheDir.toPath().resolve(projectName).resolve(hash.getValueHexString() + "-" + inputs.getTaskFactory().getOutputType());
    }

    @Override
    protected Path successMarker(Path taskDir) {
        return taskDir.resolve("success");
    }

    @Override
    protected Path failureMarker(Path taskDir) {
        return taskDir.resolve("failure");
    }

    @Override
    protected Path logFile(Path taskDir) {
        return taskDir.resolve("output.log");
    }

    @Override
    protected Path outputDir(Path taskDir) {
        return taskDir.resolve("results");
    }
}
