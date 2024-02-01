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

    /**
     * If this dependency is a maven external dependency, return the jar file that represents it.
     * @return File pointing at the jar, or null if this is a maven reactor project.
     */
    File getJar();

    /**
     *  If this project is a maven external dependency and has an annotation processors in it,
     *  return the set of declared processors. If this is a maven reactor project (with annotataion processor or not),
     *  return an empty set.
     */
    Set<String> getProcessors();

}
