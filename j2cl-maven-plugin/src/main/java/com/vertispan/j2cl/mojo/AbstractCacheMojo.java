/*
 * Copyright © 2019 j2cl-maven-plugin authors
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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class AbstractCacheMojo extends AbstractMojo {
    /**
     * Specifies the path to the build cache. This defaults to a directory in {@code target}, but for easier reuse
     * and faster builds between projects, it can make sense to set this globally to a shared directory.
     */
    @Parameter(defaultValue = "${project.build.directory}/gwt3BuildCache", required = true, property = "gwt3.cache.dir")
    private File gwt3BuildCacheDir;

    @Parameter(defaultValue = "${project.build.directory}/j2cl-maven-plugin-local-cache", required = true)
    protected File localBuildCache;

    protected Path getCacheDir() {
        PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().get("pluginDescriptor");
        String pluginVersion = pluginDescriptor.getVersion();

        return Paths.get(gwt3BuildCacheDir.getAbsolutePath(), pluginVersion);
    }
}
