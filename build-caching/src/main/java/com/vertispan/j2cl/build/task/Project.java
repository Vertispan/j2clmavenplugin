package com.vertispan.j2cl.build.task;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 *
 */
public interface Project {
    String getKey();

    List<? extends Dependency> getDependencies();

    boolean hasSourcesMapped();

    /**
     * NOTE: This method may not exist for long, if a cleaner approach can be found to handling
     * archives with JS content that shouldn't have javac/j2cl run on them.
     *
     * @return true if this project should only be used for its JS content, false otherwise
     */
    boolean isJsZip();

    File getJar();

    /**
     * @return the set of annotation processors that should be run on this project
     */
    Set<String> getProcessors();

}
