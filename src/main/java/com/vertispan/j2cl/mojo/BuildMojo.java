package com.vertispan.j2cl.mojo;

import com.google.javascript.jscomp.DependencyOptions;
import com.vertispan.j2cl.build.*;
import net.cardosi.mojo.AbstractBuildMojo;
import net.cardosi.mojo.ClosureBuildConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;

@Mojo(name = "build", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
//@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class BuildMojo extends AbstractBuildMojo implements ClosureBuildConfiguration {

    @Override
    public String getClasspathScope() {
        return null;
    }

    @Override
    public List<String> getEntrypoint() {
        return null;
    }

    @Override
    public Set<String> getExterns() {
        return null;
    }

    @Override
    public Map<String, String> getDefines() {
        return null;
    }

    @Override
    public String getWebappDirectory() {
        return null;
    }

    @Override
    public String getInitialScriptFilename() {
        return null;
    }

    @Override
    public String getCompilationLevel() {
        return null;
    }

    @Override
    public DependencyOptions.DependencyMode getDependencyMode() {
        return null;
    }

    @Override
    public boolean getRewritePolyfills() {
        return false;
    }

    @Override
    public boolean getCheckAssertions() {
        return false;
    }

    @Override
    public boolean getSourcemapsEnabled() {
        return false;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        //accumulate configs
        Map<String, String> config = null;

        // build project from maven project and dependencies, recursively
        Project p = new Project();

        // given the build output, determine what tasks we're going to run
        String outputTask = getCompilationLevel();

        // construct other required elements to get the work done
        final DiskCache diskCache;
        try {
            diskCache = new DefaultDiskCache(gwt3BuildCacheDir);
        } catch (IOException ioException) {
            throw new MojoExecutionException("Failed to create cache", ioException);
        }
        TaskScheduler taskScheduler = new TaskScheduler(Executors.newFixedThreadPool(4), diskCache);
        TaskRegistry taskRegistry = new TaskRegistry(new HashMap<>());
//        WatchService watchService = new WatchService();


        // Given these, start processing the work needed for the given output we want
        BuildService buildService = new BuildService(taskRegistry, taskScheduler, diskCache);
        buildService.assignProject(p, outputTask, config);

        // initial update (or expect assignProjects to scan?)
        buildService.updateFiles();





    }
}
