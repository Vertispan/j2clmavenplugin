package com.vertispan.j2cl.build.task;

/**
 * Default output types - these are just strings, extensions can provide their own
 * as well.
 */
public interface OutputTypes {
    /**
     * A special output type to indicate to use project's own sources.
     */
    String INPUT_SOURCES = "input_sources";

    /**
     * Annotation processor output.
     */
    String GENERATED_SOURCES = "generated_sources";
    /**
     * Bytecode from the original and generated sources. Not suitable
     * for j2cl, only meant to be used for downstream generated sources
     * tasks that need a compile classpath and will generate sources
     * that also need to be stripped.
     */
    String BYTECODE = "bytecode";

    /**
     * Sources where the Java code has had GwtIncompatible members stripped.
     */
    String STRIPPED_SOURCES = "stripped_sources";
    /**
     * Java bytecode with GwtIncompatible members have been stripped.
     */
    String STRIPPED_BYTECODE = "stripped_bytecode";
    /**
     * Simplified Java bytecode with GwtIncomatible members removed.
     */
    String STRIPPED_BYTECODE_HEADERS = "stripped_bytecode_headers";

    /**
     * J2CL output, and other JS sources.
     */
    String TRANSPILED_JS = "transpiled_js";

    //TODO accumulate other JS from classpath, perhaps built into bundle and optimized instead of making j2cl copy them?

    /**
     * Simplified JS sources.
     */
    @Deprecated //this probably won't exist for long, see TODO above
    String TRANSPILED_JS_HEADERS = "transpiled_js_headers";

    /**
     * Single JS file with all sources, unpruned, from a project
     */
    String BUNDLED_JS = "bundled_js";

    /**
     * Runnable app including all bundled_js files from a project's runtime classpath
     */
    String BUNDLED_JS_APP = "bundled_js_app";

    /**
     * Optimized build including all js from a project's runtime classpath
     */
    String OPTIMIZED_JS = "optimized_js";
}
