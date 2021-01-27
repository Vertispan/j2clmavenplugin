package net.cardosi.mojo.build;

/**
 * A dependency is a reference to another project's contents, scoped to indicate whether these are
 * required to be compiled against, or linked against (and so are required at runtime). The default
 * is to be used for both purposes, but in some cases it is appropriate to only select one.
 *
 * This build tooling doesn't automatically resolve transitive dependencies or handle conflicts, but
 * assumes that each project's dependencies are already resolved.
 */
public class Dependency {
    public enum Scope {
        COMPILE,
        RUNTIME,
        BOTH;

        private boolean isCompileScope() {
            return this != RUNTIME;
        }

        private boolean isRuntimeScope() {
            return this != COMPILE;
        }

        /**
         * TODO this isn't clear how you use it, probably should split into two enums, or offer an enumset
         */
        private boolean matches(Scope scope) {
            switch (this) {
                case COMPILE:
                    return scope.isCompileScope();
                case RUNTIME:
                    return scope.isRuntimeScope();
                case BOTH:
                    return scope == BOTH;
            }
            return false;
        }
    }
    private Project project;

    private Scope scope = Scope.BOTH;

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }
}
