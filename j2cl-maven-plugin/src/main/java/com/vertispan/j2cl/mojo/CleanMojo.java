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
package com.vertispan.j2cl.mojo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A goal to clean cached J2cl/GWT3 build outputs. This goal is meant to support the case where the cache
 * is moved outside of the default location in {@code target}, and handle the cases where the user needs to
 * manually run clean.
 *
 * When working on more than one j2cl-maven-plugin project locally, it can he helpful to specify a global
 * shared cache that all projects should share, so that when any artifact is built it can be reused.
 *
 */
@Mojo(name = "clean", aggregator = true)
public class CleanMojo extends AbstractCacheMojo {

    /**
     * The artifact that should be cleaned. If not specified, all artifacts in the current reactor will be cleaned.
     * Specify {@code *} to indicate that the entire cache should be cleaned.
     */
    @Parameter(property = "artifact")
    protected String artifact;

    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path currentPluginCacheDir = getCacheDir();

        if (Files.notExists(currentPluginCacheDir)) {
            getLog().info("Directory doesn't exist, nothing to clean: " + currentPluginCacheDir);
            return;
        }

        try {
            if (artifact == null) {
                // delete all cache entries from the current reactor
                for (MavenProject reactorProject : reactorProjects) {
                    getLog().info("Deleting cache for " + reactorProject);
                    deleteArtifact(currentPluginCacheDir, reactorProject.getArtifactId());
                }

            } else if (artifact.equals("*")) {
                // delete all cache entries, regardless of source
                getLog().info("Deleting entire j2cl-maven-plugin build cache");
                recursivelyDeleteDir(currentPluginCacheDir);
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
        Pattern artifactPattern = Pattern.compile(Pattern.quote(artifact + "-") + "[^-]+");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(baseDir)) {
            for (Path entry : entries) {
                if (artifactPattern.matcher(entry.getFileName().toString()).matches()) {
                    recursivelyDeleteDir(entry);
                    getLog().info("Deleted directory " + entry);
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
            Files.deleteIfExists(path);
        }
    }
}
