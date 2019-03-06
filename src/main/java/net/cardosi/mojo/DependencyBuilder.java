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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;

/**
 * Class used to create a Dependency tree for a given artifact
 */
public class DependencyBuilder {

    /**
     * Logger
     */
    private static Log log = new SystemStreamLog();

    /**
     * Retrieve a <code>List</code> of the dependency files ordered from last children (= without dependency) to root
     * @param session
     * @param dependencyGraphBuilder
     * @param project
     * @param reactorProjects
     * @param scope
     * @return
     * @throws MojoExecutionException
     */
    public static List<File> getOrderedClasspath(MavenSession session, DependencyGraphBuilder dependencyGraphBuilder, MavenProject project, List<MavenProject> reactorProjects, String scope) throws MojoExecutionException {
        final DependencyNode dependencyNode = getDependencyNode(session, dependencyGraphBuilder, project, reactorProjects, null);
        List<File> toReturn = new ArrayList<>();
        recursivelyPopulateList(toReturn, dependencyNode);
        return getDependencyNodeList(dependencyNode);
//        recursivelyPopulateList(toReturn, dependencyNode);
//        return toReturn;
    }

    // private methods --------------------------------------------------------

    /**
     * Insert the <code>DependencyNode</code>' artifact <code>File</code> (if not null) to the given <code>List</code>
     * at the first (index 0) position
     * @param toPopulate
     * @param dependencyNode
     */
    private static void recursivelyPopulateList(List<File> toPopulate, DependencyNode dependencyNode) {
        final File nodeFile = dependencyNode.getArtifact().getFile();
        if (nodeFile != null) {
            toPopulate.add(nodeFile);
        }
        dependencyNode.getChildren().forEach(child -> {
            File childNodeFile = child.getArtifact().getFile();
            if (childNodeFile != null) {
                toPopulate.add(childNodeFile);
            }
        });
        for (DependencyNode child : dependencyNode.getChildren()) {
            child.getChildren().forEach(nephew -> recursivelyPopulateList(toPopulate, nephew));
        }
    }

    /**
     * Use a <code>CollectingDependencyNodeVisitor</code> to retrieve a list of <code>File</code> of the dependent <code>Artifatcs</code>
     * @param toVisit
     * @return
     * @throws Exception
     */
    private static List<File> getDependencyNodeList(DependencyNode toVisit) throws MojoExecutionException {
        try {
            CollectingDependencyNodeVisitor visitor = new CollectingDependencyNodeVisitor();
            toVisit.accept(visitor);
            List<File> toReturn = visitor.getNodes()
                    .stream()
                    .map(dependencyNode -> dependencyNode.getArtifact().getFile())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            Collections.reverse(toReturn);
            return toReturn;
        } catch (Throwable t) {
            throw new MojoExecutionException("Failed to get dependencies file list due to " + t.getMessage());
        }
    }

    /**
     * Retrieve the <code>DependencyNode</code> of the given <code>MavenProject</code>
     * @param session
     * @param dependencyGraphBuilder
     * @param project
     * @param reactorProjects
     * @param scope
     * @return
     * @throws MojoExecutionException
     */
    private static DependencyNode getDependencyNode(MavenSession session, DependencyGraphBuilder dependencyGraphBuilder, MavenProject project, List<MavenProject> reactorProjects, String scope) throws MojoExecutionException {
        try {
            // TODO: note that filter does not get applied due to MSHARED-4
            ArtifactFilter artifactFilter = createResolvingArtifactFilter(scope);
            ProjectBuildingRequest buildingRequest =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setProject(project);
            // non-verbose mode use dependency graph component, which gives consistent results with Maven version
            // running
            return dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null, reactorProjects);
        } catch (DependencyGraphBuilderException exception) {
            throw new MojoExecutionException("Cannot build project dependency graph", exception);
        }
    }

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
