package com.vertispan.j2cl.mojo;

public enum AnnotationProcessorMode {
    PREFER_MAVEN(false, false),
    IGNORE_MAVEN(true, false),
    AVOID_MAVEN(true, true);

    private final boolean pluginShouldRunApt;
    private final boolean pluginShouldExcludeGeneratedAnnotationsDir;

    AnnotationProcessorMode(boolean pluginShouldRunApt, boolean pluginShouldExcludeGeneratedAnnotationsDir) {
        this.pluginShouldRunApt = pluginShouldRunApt;
        this.pluginShouldExcludeGeneratedAnnotationsDir = pluginShouldExcludeGeneratedAnnotationsDir;
    }

    public boolean pluginShouldRunApt() {
        return pluginShouldRunApt;
    }
    public boolean pluginShouldExcludeGeneratedAnnotationsDir() {
        return pluginShouldExcludeGeneratedAnnotationsDir;
    }
}
