package com.vertispan.j2cl.mojo;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import java.util.List;

/**
 * Config wiring to indicate that some dependency should be universally replaced with some
 * other dependency (possibly not yet resolved). This will necessarily change the hash for
 * any project which uses the original dependency, so that two projects with different
 * dependency replacement rules may not share one or more cache entries, since those
 * cache entries were built against different dependencies.
 *
 * Note that replacement may be null/blank, indicating that the original should just be
 * removed when compiling with this plugin.
 *
 * POTENTIAL FUTURE FEATURE:
 * If the replacement coordinate is provided and it is resolvable in the current project, that
 * will be used instead of relying on the version specified in the coordinates. Otherwise, the
 * precise coordinates will be followed.
 *
 * Also affects closure/bundle, but isn't designed specifically to be used for that purpose,
 * use normal maven dependency tools (exclusions, etc) for that.
 */
public class DependencyReplacement {
    private String original;
    private String replacement;

    private Artifact replacementArtifact;

    @SuppressWarnings("unused")// created reflectively from the pom.xml
    public DependencyReplacement() {
    }

    public DependencyReplacement(String original, String replacement) {
        this.original = original;
        this.replacement = replacement;
    }

    public String getOriginal() {
        return original;
    }

    public void setOriginal(String original) {
        this.original = original;
    }

    public String getReplacement() {
        return replacement;
    }

    public void setReplacement(String replacement) {
        this.replacement = replacement;
    }

    /**
     * Given details about the current project and its dependencies, resolves the "replacement" to a specific dependency
     * if ambiguous.
     * @param repoSession the current RepositorySystemSession
     * @param repositories the current list of RemoteRepository instances
     * @param repoSystem the current RepositorySystem
     */
    public void resolve(RepositorySystemSession repoSession, List<RemoteRepository> repositories, RepositorySystem repoSystem) {
        if (replacementArtifact != null || replacement == null) {
            return;
        }

        ArtifactRequest request = new ArtifactRequest()
                .setRepositories(repositories)
                .setArtifact(new DefaultArtifact(replacement));

        try {
            replacementArtifact = RepositoryUtils.toArtifact(repoSystem.resolveArtifact(repoSession, request).getArtifact());
        } catch (ArtifactResolutionException e) {
            throw new IllegalArgumentException("Failed to find artifact " + replacement, e);
        }
    }

    /**
     * True of the original coordinates either match the given dependency, or are a transitive dependency from it.
     * @param dependency the dependency to test
     * @return true if this instance matches the given dependency (and so {@link #getReplacementArtifact} should be called)
     */
    public boolean matches(Artifact dependency) {
        // check if we are an exact match, or if the given dependency is a transitive dependency of what we are removing
        return matchesExactly(dependency) || dependency.getDependencyTrail().stream().anyMatch(coords -> coords.equals(original) || coords.startsWith(original + ":"));
    }

    private boolean matchesExactly(Artifact dependency) {
        return ArtifactUtils.versionlessKey(dependency).equals(original);
    }

    /**
     * Returns the replacement artifact that this rule provides for the given dependency. This will be null in two cases:
     *   o  if there is no replacement to provide
     *   o  if the given dependency is itself a transitive dependency, so we don't directly replace it
     *
     * @param dependency the dependency to examine and optionally replace
     * @return null to remove the dependency, otherwise the new dependency to add to the project
     */
    public Artifact getReplacementArtifact(Artifact dependency) {
        if (matchesExactly(dependency)) {
            return replacementArtifact;
        }
        return null;
    }
}