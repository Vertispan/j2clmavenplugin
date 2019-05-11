package net.cardosi.mojo.cache;

import com.google.javascript.jscomp.PersistentInputStore;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DiskCache {
    private final ExecutorService s = Executors.newFixedThreadPool(1);//TODO make this configurable, confirm it is actually thread-safe

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final String pluginVersion;
    private final File jsZipCacheDir;

    private final File bootstrap;
    private final List<File> extraClasspath;
    private final List<File> extraJsZips;
    private final PersistentInputStore persistentInputStore = new PersistentInputStore();

    public DiskCache(String pluginVersion, File jsZipCacheDir, File bootstrap, List<File> extraClasspath, List<File> extraJsZips) {

        this.pluginVersion = pluginVersion;
        this.bootstrap = bootstrap;
        this.extraClasspath = extraClasspath;
        this.extraJsZips = extraJsZips;

        this.jsZipCacheDir = new File(jsZipCacheDir, pluginVersion);
        this.jsZipCacheDir.mkdirs();
    }

    public void takeLock() {
        lock.writeLock().lock();
    }
    public void release() {
        lock.writeLock().unlock();
    }


    public TranspiledCacheEntry entry(String hash, String artifactId) {

        File cacheDir = new File(jsZipCacheDir, hash + "-" + artifactId);
        cacheDir.mkdirs();

        return new TranspiledCacheEntry(hash, artifactId, cacheDir);
    }

    public ExecutorService pool() {
        return s;
    }

    public File getBootstrap() {
        return bootstrap;
    }

    public List<File> getExtraClasspath() {
        return extraClasspath;
    }

    public List<File> getExtraJsZips() {
        return extraJsZips;
    }

    public String getPluginVersion() {
        return pluginVersion;
    }

    public PersistentInputStore getPersistentInputStore() {
        return persistentInputStore;
    }

}
