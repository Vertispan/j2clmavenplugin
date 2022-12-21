package com.vertispan.j2cl.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class AbstractCacheMojo extends AbstractMojo {
    /**
     * Specifies the path to the build cache. This defaults to a directory in {@code target}, but for easier reuse
     * and faster builds between projects, it can make sense to set this globally to a shared directory.
     */
    @Parameter(defaultValue = "${project.build.directory}/gwt3BuildCache", required = true, property = "gwt3.cache.dir")
    private File gwt3BuildCacheDir;

    @Parameter(defaultValue = "${project.build.directory}/j2cl-maven-plugin-local-cache", required = true)
    protected File localBuildCache;

    protected Path getCacheDir() {
        PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().get("pluginDescriptor");
        String pluginVersion = pluginDescriptor.getVersion();

        return Paths.get(gwt3BuildCacheDir.getAbsolutePath(), pluginVersion);
    }
}
