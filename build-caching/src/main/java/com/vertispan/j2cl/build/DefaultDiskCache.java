package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import io.methvin.watcher.hashing.Murmur3F;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executor;

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
     * @param executor executor to submit notification that another task can proceed
     */
    public DefaultDiskCache(File cacheDir, Executor executor) throws IOException {
        super(cacheDir, executor);
    }

    @Override
    protected Path taskDir(CollectedTaskInputs inputs) {
        String projectName = inputs.getProject().getKey();
        Murmur3F hash = new Murmur3F();

        hash.update(inputs.getTaskFactory().getClass().toString().getBytes(StandardCharsets.UTF_8));
        hash.update(inputs.getTaskFactory().getTaskName().getBytes(StandardCharsets.UTF_8));
        hash.update(inputs.getTaskFactory().getVersion().getBytes(StandardCharsets.UTF_8));

        for (Input input : inputs.getInputs()) {
            input.updateHash(hash);
        }

        for (Map.Entry<String, String> entry : inputs.getUsedConfigs().entrySet()) {
            hash.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            if (entry.getValue() == null) {
                hash.update(0);
            } else {
                hash.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }

        return cacheDir.toPath().resolve(projectName.replaceAll("[^\\-_a-zA-Z0-9.]", "-")).resolve(hash.getValueHexString() + "-" + inputs.getTaskFactory().getOutputType());
    }

    @Override
    protected Path successMarker(Path taskDir) {
        return taskDir.resolve("status/success");
    }

    @Override
    protected Path failureMarker(Path taskDir) {
        return taskDir.resolve("status/failure");
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
