/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
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
package net.cardosi.mojo;

import net.cardosi.mojo.cache.DiskCache;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

@Mojo(name = "clean")
public class CleanMojo extends AbstractGwt3BuildMojo {

    @Parameter(property = "artifact")
    protected String artifact;

    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().get("pluginDescriptor");
        String pluginVersion = pluginDescriptor.getVersion();

        Path currentPluginCacheDir = Paths.get(gwt3BuildCacheDir.getAbsolutePath(), pluginVersion);

        try {
            if (artifact == null) {
                // delete all cache entries from the current reactor
                for (MavenProject reactorProject : reactorProjects) {
                    getLog().info("Deleting cache for " + reactorProject);
                    deleteArtifact(currentPluginCacheDir, reactorProject.getArtifactId());
                }

            } else if (artifact.equals("*")) {
                // delete all cache entries, regardless of source
                getLog().info("Deleting entire GWT3 build cache");
                recursivelyDeleteDir(gwt3BuildCacheDir.toPath());
            } else {
                // identify the cache entry we were ask to remove and delete all items found which might
                // match that
                    getLog().info("Deleting cache for " + artifact);
                deleteArtifact(currentPluginCacheDir, artifact);
            }
        } catch (IOException t) {
            throw new MojoFailureException(t.getMessage(), t);
        }
    }

    private void deleteArtifact(Path baseDir, String artifact) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(baseDir)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().startsWith(artifact + "-")) {
                    getLog().info("Deleting directory " + entry);
                    recursivelyDeleteDir(entry);
                }
            }
        }
    }

    private static void recursivelyDeleteDir(Path path) throws IOException {
        if (Files.exists(path)) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                    for (Path entry : entries) {
                        recursivelyDeleteDir(entry);
                    }
                }
            }
            Files.delete(path);
        }
    }
}
