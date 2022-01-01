package net.cardosi.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

public abstract class AbstractCacheMojo extends AbstractMojo {
    /**
     * Specifies the path to the build cache. This defaults to a directory in {@code target}, but for easier reuse
     * and faster builds between projects, it can make sense to set this globally to a shared directory.
     */
    @Parameter(defaultValue = "${project.build.directory}/gwt3BuildCache", required = true, property = "gwt3.cache.dir")
    protected File gwt3BuildCacheDir;
}
