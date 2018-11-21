package net.cardosi;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.javascript.jscomp.CompilerOptions;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Abstract claa to define commonly used parameters
 */
public abstract class AbstractJ2CLMojo extends AbstractMojo {

    @Parameter(property = "sourceDir", /*usage = "specify one or more java source directories",*/ required = true)
    protected List<String> sourceDir = Arrays.asList("src/main/java");

    @Parameter(name = "bytecodeClasspath", /*usage = "java classpath. bytecode jars are assumed to be pre-" +
                "processed, source jars will be preprocessed, transpiled, and cached. This is only " +
                "done on startup, sources that should be monitored for changes should be passed in " +
                "via -src",*/ required = true)
    protected String bytecodeClasspath;

    @Parameter(name = "j2clClasspath", /*usage = "specify js archive classpath that won't be " +
                "transpiled from sources or classpath. If nothing else, should include " +
                "bootstrap.js.zip and jre.js.zip", */required = true)
    protected String j2clClasspath;

    @Parameter(name = "outputJsPathDir", /*usage = "indicates where to write generated JS sources, sourcemaps, " +
                "etc. Should be a directory specific to gwt, anything may be overwritten there, " +
                "but probably should be somewhere your server will pass to the browser",*/ required = true)
    protected String outputJsPathDir;

    @Parameter(name = "classesDir", /* usage = "provide a directory to put compiled bytecode in. " +
                "If not specified, a tmp dir will be used. Do not share this directory with " +
                "your IDE or other build tools, unless they also pre-process j2cl sources",*/ defaultValue = "${basedir}/target/gen-bytecode")
    protected String classesDir;

    @Parameter(name = "entrypoint", /*aliases = "--entry_point",
                usage = "one or more entrypoints to start the app with, from either java or js", */required = true)
    protected List<String> entrypoint = new ArrayList<>();

    @Parameter(name = "jsZipCacheDir", /*usage = "directory to cache generated jszips in. Should be " +
                "cleared when j2cl version changes", */required = true)
    protected String jsZipCacheDir;

    //lifted straight from closure for consistency
    @Parameter(name = "define"/* ,
               aliases = {"--D", "-D"},
                usage = "Override the value of a variable annotated @define. "
                        + "The format is <name>[=<val>], where <name> is the name of a @define "
                        + "variable and <val> is a boolean, number, or a single-quoted string "
                        + "that contains no single quotes. If [=<val>] is omitted, "
                        + "the variable is marked true"*/)
    protected List<String> define = new ArrayList<>();

    //lifted straight from closure for consistency
    @Parameter(name = "externs"/*,
                usage = "The file containing JavaScript externs. You may specify"
                        + " multiple"*/)
    protected List<String> externs = new ArrayList<>();

    //lifted straight from closure for consistency
    @Parameter(
            name = "compilationLevel"/*,
                aliases = {"-O"},
                usage =
                        "Specifies the compilation level to use. Options: "
                                + "BUNDLE, "
                                + "WHITESPACE_ONLY, "
                                + "SIMPLE (default), "
                                + "ADVANCED"*/
    )
    protected String compilationLevel = "BUNDLE";

    //lifted straight from closure for consistency
    @Parameter(
            name = "languageOut"/*,
                usage =
                        "Sets the language spec to which output should conform. "
                                + "Options: ECMASCRIPT3, ECMASCRIPT5, ECMASCRIPT5_STRICT, "
                                + "ECMASCRIPT6_TYPED (experimental), ECMASCRIPT_2015, ECMASCRIPT_2016, "
                                + "ECMASCRIPT_2017, ECMASCRIPT_NEXT, NO_TRANSPILE"*/
    )
    protected String languageOut = "ECMASCRIPT5";

    //lifted straight from closure for consistency (this should get a rewrite to clarify that for gwt-like
    // behavior, NONE should be avoided. Default changed to strict.
    @Parameter(
            name = "dependencyMode"/*,
                usage = "Specifies how the compiler should determine the set and order "
                        + "of files for a compilation. Options: NONE the compiler will include "
                        + "all src files in the order listed, STRICT files will be included and "
                        + "sorted by starting from namespaces or files listed by the "
                        + "--entry_point flag - files will only be included if they are "
                        + "referenced by a goog.require or CommonJS require or ES6 import, LOOSE "
                        + "same as with STRICT but files which do not goog.provide a namespace "
                        + "and are not modules will be automatically added as "
                        + "--entry_point entries. "//Defaults to NONE."*/
    )
    protected CompilerOptions.DependencyMode dependencyMode = CompilerOptions.DependencyMode.STRICT;

    // j2cl-specific flag
    @Parameter(name = "declareLegacyNamespaces"/*,
                usage =
                        "Enable goog.module.declareLegacyNamespace() for generated goog.module().",
                hidden = true*/
    )
    protected boolean declareLegacyNamespaces = false;

    @Parameter(name = "intermediateJsPath"/*,
                usage =
                        "Enable goog.module.declareLegacyNamespace() for generated goog.module().",
                hidden = true*/, defaultValue = "${basedir}/target/js-sources"
    )
    protected String intermediateJsPath;

    @Parameter(name = "generatedClassesDir"/*,
                usage =
                        "Enable goog.module.declareLegacyNamespace() for generated goog.module().",
                hidden = true*/, defaultValue = "${basedir}/target/gen-classes"
    )
    protected String generatedClassesDir;

    protected Map<String, File> getWorkingDirs() {
        Map<String, File> toReturn = new HashMap<>();
        getLog().info("intermediateJsPath " + intermediateJsPath);
        toReturn.put(intermediateJsPath, new File(intermediateJsPath));
        getLog().info("generatedClassesDir " + generatedClassesDir);
        toReturn.put(generatedClassesDir, new File(generatedClassesDir));
        getLog().info("outputJsPathDir " + outputJsPathDir);
        toReturn.put(outputJsPathDir, new File(outputJsPathDir));
        getLog().info("classesDir " + classesDir);
        toReturn.put(classesDir, new File(classesDir));
        getLog().info("jsZipCacheDir " + jsZipCacheDir);
        toReturn.put(jsZipCacheDir, new File(jsZipCacheDir));
        return toReturn;
    }

}
