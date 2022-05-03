package com.vertispan.j2cl.build.task;

import java.io.File;

public interface Dependency {
    Project getProject();

    Scope getScope();

    File getJar();

    enum Scope {
        COMPILE,
        RUNTIME,
        BOTH;

        public boolean isCompileScope() {
            return this != RUNTIME;
        }

        public boolean isRuntimeScope() {
            return this != COMPILE;
        }
    }
}
