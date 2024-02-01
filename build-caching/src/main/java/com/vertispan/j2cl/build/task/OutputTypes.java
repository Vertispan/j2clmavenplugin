/*
 * Copyright © 2021 j2cl-maven-plugin authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vertispan.j2cl.build.task;

/**
 * Default output types - these are just strings, extensions can provide their own
 * as well.
 */
public interface OutputTypes {
    /**
     * A special output type to indicate to use project's own sources, or
     * the contents of an external dependency's jar/zip file.
     */
    String INPUT_SOURCES = "input_sources";

    /**
     * Represents the contents of a project if it were built into a jar
     * file as an external dependency. Ostensibly should contain the
     * un-stripped bytecode for a project and all of its resources (so
     * that downstream projects can look for those resources on the
     * classpath), but also presently ends up holding generated resources
     * (so that the {@link #GENERATED_SOURCES} task can copy them out),
     * and at that point it might as well contain the original Java
     * sources too, unstripped. Including those sources however means
     * that this becomes the source of truth for stripping sources,
     * rather than the union of {@link #INPUT_SOURCES} and {@link #GENERATED_SOURCES}.
     * This conflict arises since there could be .js files in the original
     * sources, and we must copy them here since downstream projects
     * could require them on the classpath - and after APT runs, we can't
     * tell which sources were copied in and which came from sources,
     * so we can't let downstream closure point to this (or generated
     * sources) and input sources, it will find duplicate files.
     */
    String BYTECODE = "bytecode";

    /**
     * Formerly annotation processor output.
     *
     * DISABLED FOR NOW - reintroducing this would require an intermediate
     * output that would feed .java files to this, and everything else to
     * {@link #BYTECODE} including source .js files. Taking this step may
     * be necessary for better incremental builds.
     */
    @Deprecated
    String GENERATED_SOURCES = "generated_sources";

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
