package com.vertispan.j2cl.build.task;

import java.util.List;

/**
 *
 */
public interface Project {
    String getKey();

    List<? extends Dependency> getDependencies();

    boolean hasSourcesMapped();
}
