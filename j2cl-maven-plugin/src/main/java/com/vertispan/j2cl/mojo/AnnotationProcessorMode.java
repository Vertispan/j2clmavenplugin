/*
 * Copyright © 2022 j2cl-maven-plugin authors
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
