package com.vertispan.j2cl.build.task;

public interface Dependency {
    Project getProject();

    Scope getScope();

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
