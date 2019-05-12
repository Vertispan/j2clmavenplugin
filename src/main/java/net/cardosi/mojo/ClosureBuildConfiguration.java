package net.cardosi.mojo;

import java.util.List;

/**
 * Describes how to run the Closure Compiler in a j2cl-based project, in a way that we can implement
 * not just in goals but also wrap up a xml dom and pass it around. All implementations of this must
 * be consistent in how they name their parameters to avoid driving users insane - naming should be
 * derrived from the property names.
 */
public interface ClosureBuildConfiguration {
    String getClasspathScope();

    List<String> getEntrypoint();

    List<String> getExterns();

//    List<String> getDefines();

    String getWebappDirectory();

    String getInitialScriptFilename();

    String getCompilationLevel();

//    List<String> getIncludedJsZips();
}
