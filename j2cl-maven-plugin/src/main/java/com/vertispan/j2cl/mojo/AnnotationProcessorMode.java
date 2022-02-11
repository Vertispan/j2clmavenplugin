package com.vertispan.j2cl.mojo;

/**
 * Currently unused, this enum is meant to give the pom.xml author the power to decide when
 * the j2cl plugin should generate code via annotation processors, and when the maven build
 * will take care of that.
 *
 * For now, only IGNORE_MAVEN is properly supported, it seems that maven doesn't include the
 * generated-sources/annotations directory to reactor projects not currently being evaluated,
 * so we might need to ignore the source directory entirely, and just consume the "artifact"
 * of that reactor project (which might be the target/classes directory, etc).
 *
 * See https://github.com/Vertispan/j2clmavenplugin/issues/112
 */
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
