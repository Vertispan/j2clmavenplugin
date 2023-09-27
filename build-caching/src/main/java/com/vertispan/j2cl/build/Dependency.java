package com.vertispan.j2cl.build;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A dependency is a reference to another project's contents, scoped to indicate whether these are
 * required to be compiled against, or linked against (and so are required at runtime). The default
 * is to be used for both purposes, but in some cases it is appropriate to only select one.
 *
 * This build tooling doesn't automatically resolve transitive dependencies or handle conflicts, but
 * assumes that each project's dependencies are already resolved.
 */
public class Dependency implements com.vertispan.j2cl.build.task.Dependency {
    private Project project;

    private Scope scope = com.vertispan.j2cl.build.task.Dependency.Scope.BOTH;

    private File jar;

    private Optional<Boolean> isAPT = Optional.empty();

    private Set<String> processors = new HashSet<>();

    public boolean belongsToScope(Scope scope) {
        //TODO it is weird to let BOTH be passed as a param, probably make that impossible and clean this up
        switch (getScope()) {
            case COMPILE:
                return scope.isCompileScope();
            case RUNTIME:
                return scope.isRuntimeScope();
            case BOTH:
                return true;
            default:
                throw new IllegalStateException("Unknown scope " + getScope());
        }
    }

    @Override
    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    @Override
    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    @Override
    public boolean isAPT() {
        if (isAPT.isEmpty()) {
            if (project.isJsZip()) {
                isAPT = Optional.of(false);
                return false;
            }
            if (jar == null) {
                this.isAPT = Optional.of(false);
            } else if (jar.exists()) {
               try(ZipFile zipFile = new ZipFile(jar)) {
                    ZipEntry entry = zipFile.getEntry("META-INF/services/javax.annotation.processing.Processor");
                    if (entry != null) {
                       this.isAPT = Optional.of(true);
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                processors.add(line);
                            }
                            if(!processors.isEmpty()) {
                                this.isAPT = Optional.of(true);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        this.isAPT = Optional.of(false);
                    }
               } catch (IOException e) {
                   throw new RuntimeException(e);
               }
            }
        }
        return isAPT.get();
    }

    @Override
    public File getJar() {
        return jar;
    }

    public void setJar(File jar) {
        this.jar = jar;
    }

    @Override
    public Set<String> getProcessors() {
        return processors;
    }

}
