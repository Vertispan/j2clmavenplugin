package net.cardosi.mojo.options;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Preconditions;
import com.google.javascript.jscomp.CompilerOptions;
import org.kohsuke.args4j.Option;

import static com.google.common.io.Files.createTempDir;

public class Gwt3OptionsImpl implements Gwt3Options {
    @Option(name = "-src", usage = "specify one or more java source directories", required = true)
    private
    List<String> sourceDir = new ArrayList<>();
    @Option(name = "-classpath", usage = "java classpath. bytecode jars are assumed to be pre-" +
            "processed, source jars will be preprocessed, transpiled, and cached. This is only " +
            "done on startup, sources that should be monitored for changes should be passed in " +
            "via -src", required = true)
    List<String> bytecodeClasspath;

    @Option(name = "-jsClasspath", usage = "specify js archive classpath that won't be " +
            "transpiled from sources or classpath. If nothing else, should include " +
            "bootstrap.js.zip and jre.js.zip", required = true)
    List<String> j2clClasspath;

    @Option(name = "-out", usage = "indicates where to write generated JS sources, sourcemaps, " +
            "etc. Should be a directory specific to gwt, anything may be overwritten there, " +
            "but probably should be somewhere your server will pass to the browser", required = true)
    String outputJsPathDir;

    @Option(name = "-classes", usage = "provide a directory to put compiled bytecode in. " +
            "If not specified, a tmp dir will be used. Do not share this directory with " +
            "your IDE or other build tools, unless they also pre-process j2cl sources")
    String classesDir;

    @Option(name = "-entrypoint", aliases = "--entry_point",
            usage = "one or more entrypoints to start the app with, from either java or js")
    List<String> entrypoint = new ArrayList<>();

    @Option(name = "-jsZipCache", usage = "directory to cache generated jszips in. Should be " +
            "cleared when j2cl version changes", required = true)
    String jsZipCacheDir;

    //lifted straight from closure for consistency
    @Option(name = "--define",
            aliases = {"--D", "-D"},
            usage = "Override the value of a variable annotated @define. "
                    + "The format is <name>[=<val>], where <name> is the name of a @define "
                    + "variable and <val> is a boolean, number, or a single-quoted string "
                    + "that contains no single quotes. If [=<val>] is omitted, "
                    + "the variable is marked true")
    List<String> define = new ArrayList<>();

    //lifted straight from closure for consistency
    @Option(name = "--externs",
            usage = "The file containing JavaScript externs. You may specify"
                    + " multiple")
    List<String> externs = new ArrayList<>();

    //lifted straight from closure for consistency
    @Option(
            name = "--compilation_level",
            aliases = {"-O"},
            usage =
                    "Specifies the compilation level to use. Options: "
                            + "BUNDLE, "
                            + "WHITESPACE_ONLY, "
                            + "SIMPLE (default), "
                            + "ADVANCED"
    )
    String compilationLevel = "BUNDLE";

    //lifted straight from closure for consistency
    @Option(
            name = "--language_out",
            usage =
                    "Sets the language spec to which output should conform. "
                            + "Options: ECMASCRIPT3, ECMASCRIPT5, ECMASCRIPT5_STRICT, "
                            + "ECMASCRIPT6_TYPED (experimental), ECMASCRIPT_2015, ECMASCRIPT_2016, "
                            + "ECMASCRIPT_2017, ECMASCRIPT_NEXT, NO_TRANSPILE"
    )
    String languageOut = "ECMASCRIPT5";

    //lifted straight from closure for consistency (this should get a rewrite to clarify that for gwt-like
    // behavior, NONE should be avoided. Default changed to strict.
    @Option(
            name = "--dependency_mode",
            usage = "Specifies how the compiler should determine the set and order "
                    + "of files for a compilation. Options: NONE the compiler will include "
                    + "all src files in the order listed, STRICT files will be included and "
                    + "sorted by starting from namespaces or files listed by the "
                    + "--entry_point flag - files will only be included if they are "
                    + "referenced by a goog.require or CommonJS require or ES6 import, LOOSE "
                    + "same as with STRICT but files which do not goog.provide a namespace "
                    + "and are not modules will be automatically added as "
                    + "--entry_point entries. "//Defaults to NONE."
    )
    CompilerOptions.DependencyMode dependencyMode = CompilerOptions.DependencyMode.STRICT;



    // j2cl-specific flag
    @Option(name = "-declarelegacynamespaces",
            usage =
                    "Enable goog.module.declareLegacyNamespace() for generated goog.module().",
            hidden = true
    )
    boolean declareLegacyNamespaces = false;


    /**
     * Returns a path to where raw sources and sourcemaps will be in the generated JS output directory
     */
    @Override
    public String getIntermediateJsPath() {
        return createDir(outputJsPathDir + "/sources").getPath();
    }

    /**
     * Returns a directory where class files will be compiled to
     */
    @Override
    public File getClassesDir() {
        File classesDirFile;
        if (classesDir != null) {
            classesDirFile = createDir(classesDir);
        } else {
            classesDirFile = createTempDir();
            classesDir = classesDirFile.getAbsolutePath();
        }
        return classesDirFile;
    }

    /**
     * Static helper to return or create a directory at a given path
     */
    private static File createDir(String path) {
        File f = new File(path);
        if (f.exists()) {
            Preconditions.checkState(f.isDirectory(), "path already exists but is not a directory " + path);
        } else if (!f.mkdirs()) {
            throw new IllegalStateException("Failed to create directory " + path);
        }
        return f;
    }

    @Override
    public boolean isDeclareLegacyNamespaces() {
        return declareLegacyNamespaces;
    }

    @Override
    public List<String> getBytecodeClasspath() {
        return bytecodeClasspath;
    }

    @Override
    public String getJsOutputFile() {
        return outputJsPathDir + "/app.js";
    }

    @Override
    public List<String> getEntrypoint() {
        return entrypoint;
    }

    @Override
    public List<String> getDefine() {
        return define;
    }

    @Override
    public List<String> getExterns() {
        return externs;
    }

    @Override
    public String getLanguageOut() {
        return languageOut;
    }

    @Override
    public String getCompilationLevel() {
        return compilationLevel;
    }

    @Override
    public CompilerOptions.DependencyMode getDependencyMode() {
        return dependencyMode;
    }

    @Override
    public List<String> getJ2clClasspath() {
        return j2clClasspath;
    }

    @Override
    public List<String> getSourceDir() {
        return sourceDir;
    }

    @Override
    public String getJsZipCacheDir() {
        //TODO provide generated temp dir and warn if none is provided?
        return jsZipCacheDir;
    }

    public String getOutputJsPathDir() {
        return outputJsPathDir;
    }
}
