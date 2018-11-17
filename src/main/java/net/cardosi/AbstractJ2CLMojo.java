package net.cardosi;

import java.io.File;
import java.util.ArrayList;
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
    protected List<String> sourceDir = new ArrayList<>();

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


        /*
         getLog().info("intermediate js from j2cl path " + intermediateJsPath);

            generatedClassesDir.mkdir();
            getLog().info("generatedClassesDir " + generatedClassesDir);
            String sourcesNativeZipPath = File.createTempFile("proj-native", ".zip").getAbsolutePath();

            classesDirFile.mkdir();
            getLog().info("classesDirFile " + classesDirFile);
            bytecodeClasspath += ":" + classesDirFile.getAbsolutePath();
            List<File> classpath = new ArrayList<>();
            for (String path : bytecodeClasspath.split(File.pathSeparator)) {
                getLog().info("classpath.add " + path);
                classpath.add(new File(path));
            }

            List<String> javacOptions = Arrays.asList("-implicit:none");
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
            fileManager.setLocation(StandardLocation.SOURCE_PATH, Collections.emptyList());
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, Collections.singleton(generatedClassesDir));
            fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(classesDirFile));

            // put all j2clClasspath items into a list, we'll copy each time and add generated js
            List<String> baseJ2clArgs = new ArrayList<>(Arrays.asList("-cp", bytecodeClasspath, "-d", intermediateJsPath));
            if (declareLegacyNamespaces) {
                baseJ2clArgs.add("-declarelegacynamespaces");
            }

            String intermediateJsOutput = outputJsPathDir + "/app.js";
            CompilationLevel compLevel = CompilationLevel.fromString(compilationLevel);
            List<String> baseClosureArgs = new ArrayList<String>(Arrays.asList(
                    "--compilation_level", compLevel.name(),
                    "--js_output_file", intermediateJsOutput,// temp file to write to before we insert the missing line at the top
                    "--dependency_mode", dependencyMode.name(),// force STRICT mode so that the compiler at least orders the inputs
                    "--language_out", languageOut
            ));
            if (compLevel == CompilationLevel.BUNDLE) {
                // support BUNDLE mode, with no remote fetching for dependencies)
                baseClosureArgs.add("--define");
                baseClosureArgs.add("goog.ENABLE_DEBUG_LOADER=false");
            }
            for (String def : define) {
                baseClosureArgs.add("--define");
                baseClosureArgs.add(def);
            }
            for (String ep : entrypoint) {
                baseClosureArgs.add("--entry_point");
                baseClosureArgs.add(ep);
            }
            for (String extern : externs) {
                baseClosureArgs.add("--externs");
                baseClosureArgs.add(extern);
            }

            // configure a persistent input store - we'll reuse this and not the compiler for now, to cache the ASTs,
            // and still allow jscomp to be in modes other than BUNDLE
            PersistentInputStore persistentInputStore = new PersistentInputStore();

            for (String zipPath : j2clClasspath.split(File.pathSeparator)) {
                getLog().info("Verify zipPath " + zipPath);
                Preconditions.checkArgument(new File(zipPath).exists() && new File(zipPath).isFile(), "jszip doesn't exist! %s", zipPath);

                baseClosureArgs.add("--jszip");
                baseClosureArgs.add(zipPath);

                // add JS zip file to the input store - no nice digest, since so far we don't support changes to the zip
                persistentInputStore.addInput(zipPath, "0");
            }
            baseClosureArgs.add("--js");


        //pre-transpile all dependency sources to our cache dir, add those cached items to closure args
         */
    }

}
