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

import java.util.List;

import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;

/**
 * Class used to create a Dependency tree for a given artifact
 */
public class DependencyBuilder {

    /**
     * Logger
     */
    private static Log log = new SystemStreamLog();

    public static DependencyNode getDependencyNode(MavenSession session, DependencyGraphBuilder dependencyGraphBuilder, MavenProject project, List<MavenProject> reactorProjects, String scope) throws MojoExecutionException {
        try {
            // TODO: note that filter does not get applied due to MSHARED-4
            ArtifactFilter artifactFilter = createResolvingArtifactFilter(scope);
            ProjectBuildingRequest buildingRequest =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setProject(project);
            // non-verbose mode use dependency graph component, which gives consistent results with Maven version
            // running
            return dependencyGraphBuilder.buildDependencyGraph(buildingRequest, artifactFilter, reactorProjects);
        } catch (DependencyGraphBuilderException exception) {
            throw new MojoExecutionException("Cannot build project dependency graph", exception);
        }
    }

    // private methods --------------------------------------------------------

    /**
     * Gets the artifact filter to use when resolving the dependency tree.
     * @return the artifact filter
     */
    private static ArtifactFilter createResolvingArtifactFilter(String scope) {
        ArtifactFilter filter;
        // filter scope
        if (scope != null) {
            log.debug("+ Resolving dependency tree for scope '" + scope + "'");
            filter = new ScopeArtifactFilter(scope);
        } else {
            filter = null;
        }
        return filter;
    }
}
