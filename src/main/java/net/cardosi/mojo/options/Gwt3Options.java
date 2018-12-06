package net.cardosi.mojo.options;

import java.io.File;
import java.util.List;

import com.google.javascript.jscomp.CompilerOptions;

public interface Gwt3Options {

    /**
     * Returns a path to where raw sources and sourcemaps will be in the generated JS output directory
     */
    String getIntermediateJsPath();

    /**
     * Returns a directory where class files will be compiled to
     */
    File getClassesDir();

    boolean isDeclareLegacyNamespaces();

    List<String> getBytecodeClasspath();

    String getJsOutputFile();

    List<String> getEntrypoint();

    List<String> getDefine();

    List<String> getExterns();

    String getLanguageOut();

    String getCompilationLevel();

    CompilerOptions.DependencyMode getDependencyMode();

    List<String> getJ2clClasspath();

    List<String> getSourceDir();

    String getJsZipCacheDir();

    String getOutputJsPathDir();
}
