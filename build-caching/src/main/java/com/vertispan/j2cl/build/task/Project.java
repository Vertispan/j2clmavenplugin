package com.vertispan.j2cl.build.task;

import java.util.List;

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
}
