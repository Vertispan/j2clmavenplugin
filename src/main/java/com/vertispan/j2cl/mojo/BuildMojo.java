package com.vertispan.j2cl.mojo;

import com.google.javascript.jscomp.DependencyOptions;
import net.cardosi.mojo.AbstractBuildMojo;
import net.cardosi.mojo.ClosureBuildConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.util.List;
import java.util.Map;
import java.util.Set;

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




    }
}
