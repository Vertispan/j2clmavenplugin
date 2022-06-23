package com.vertispan.j2cl.build;

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
}
