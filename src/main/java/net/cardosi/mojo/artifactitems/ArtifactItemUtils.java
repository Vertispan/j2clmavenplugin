package net.cardosi.mojo.artifactitems;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

public class ArtifactItemUtils {

    /**
     * Logger
     */
    private static Log log = new SystemStreamLog();

    public static Map<String, File> getArtifactFiles(List<ArtifactItem> artifactItems, RepositorySystem repoSystem, RepositorySystemSession repoSession, List<RemoteRepository> remoteRepos) throws MojoFailureException, MojoExecutionException {
        Map<String, File> toReturn = new HashMap<>();
        for (ArtifactItem artifactItem : artifactItems) {
            File value = getArtifactFile(artifactItem, repoSystem, repoSession, remoteRepos);
            toReturn.put(artifactItem.getDestFileName(), value);
        }
        return toReturn;
    }

    public static File getArtifactFile(ArtifactItem artifactItem, RepositorySystem repoSystem, RepositorySystemSession repoSession, List<RemoteRepository> remoteRepos) throws MojoFailureException, MojoExecutionException {
        Artifact artifact;
        try {
            String artifactCoords = artifactItem.getGroupId() + ":" + artifactItem.getArtifactId() + ":" + artifactItem.getType();
            artifactCoords += StringUtils.isEmpty(artifactItem.getClassifier()) ? ":" + artifactItem.getVersion() : ":" + artifactItem.getClassifier() + ":" + artifactItem.getVersion();
            artifact = new DefaultArtifact(artifactCoords);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(remoteRepos);
        log.info("Resolving artifact " + artifact + " from " + remoteRepos);
        ArtifactResult result;
        try {
            result = repoSystem.resolveArtifact(repoSession, request);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        log.info("Resolved artifact " + artifact + "to " + result.getArtifact().getFile() + " from  " + result.getRepository());
        return result.getArtifact().getFile();
    }

    public static void copyArtifactFiles(Map<String, File> artifactFiles, File outputDirectory) throws MojoExecutionException {
        for (String destFileName : artifactFiles.keySet()) {
            copyFile(artifactFiles.get(destFileName), outputDirectory, destFileName);
        }
    }

    /**
     * Does the actual copy of the file and logging.
     * @param artifactFile represents the file to copy.
     * @param outputDirectory destination directory
     * @param destFileName file name of destination file.
     * @throws MojoExecutionException with a message if an error occurs.
     */
    public static void copyFile(File artifactFile, File outputDirectory, String destFileName) throws MojoExecutionException {
        File destFile = new File(outputDirectory, destFileName);
        try {
            log.info("Copying " + destFileName + " to " + destFile);
            if (artifactFile.isDirectory()) {
                // usual case is a future jar packaging, but there are special cases: classifier and other packaging
                throw new MojoExecutionException("Artifact has not been packaged yet. When used on reactor artifact, "
                                                         + "copy should be executed after packaging: see MDEP-187.");
            }
            FileUtils.copyFile(artifactFile, destFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Error copying artifact from " + artifactFile + " to " + destFile, e);
        }
    }

    /**
     * clean up configuration string before it can be tokenized
     * @param str The str which should be cleaned.
     * @return cleaned up string.
     */
    public static String cleanToBeTokenizedString(String str) {
        String ret = "";
        if (!StringUtils.isEmpty(str)) {
            // remove initial and ending spaces, plus all spaces next to commas
            ret = str.trim().replaceAll("[\\s]*,[\\s]*", ",");
        }

        return ret;
    }
}
