package net.cardosi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.Problems;
import com.google.j2cl.frontend.FrontendUtils;
import com.google.j2cl.frontend.FrontendUtils.FileInfo;
import com.google.j2cl.generator.NativeJavaScriptFile;
import com.google.j2cl.tools.gwtincompatible.JavaPreprocessor;
import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.PersistentInputStore;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Simple "dev mode" for j2cl+closure, based on the existing bash script. Lots of room for improvement, this
 * isn't intended to be a proposal, just another experiment on the way to one.
 * <p>
 * Assumptions:
 * o The js-compatible JRE is already on the java classpath (need not be on js). Probably not a good one, but
 * on the other hand, we may want to allow changing out the JRE (or skipping it) in favor of something else.
 * o A JS entrypoint already exists. Probably safe, should get some APT going soon as discussed, at least to
 * try it out.
 * <p>
 * Things about this I like:
 * o Treat both jars and jszips as classpaths (ease of dependency system integrations)
 * o Annotation processors are (or should be) run as an IDE would do, so all kinds of changes are picked up. I
 * think I got it right to pick up generated classes changes too...
 * <p>
 * Not so good:
 * o J2CL seems difficult to integrate (no public, uses threadlocals)
 * o Not correctly recompiling classes that require it based on dependencies
 * o Not at all convinced my javac wiring is correct
 * o Polling for changes
 */
@Mojo(name = "build")
public class Build extends AbstractJ2CLMojo {

    private static PathMatcher javaMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
    private static PathMatcher nativeJsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.native.js");
    private static PathMatcher jsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.js");

    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Start building...");
        try {
            final Map<String, File> workingDirs = getWorkingDirs();
            for (File file : workingDirs.values()) {
                getLog().debug("Creating if not exists: " + file.getPath());
                if (!file.exists() && !file.mkdir()) {
                    throw new MojoExecutionException("Failed to create " + file.getPath());
                }
            }
            File generatedClassesDirFile = workingDirs.get(generatedClassesDir);
            File classesDirFile = workingDirs.get(classesDir);
            String sourcesNativeZipPath = File.createTempFile("proj-native", ".zip").getAbsolutePath();
            bytecodeClasspath += ":" + classesDirFile.getAbsolutePath();
            List<File> classpath = new ArrayList<>();
            for (String path : bytecodeClasspath.split(File.pathSeparator)) {
                getLog().debug("classpath.add " + path);
                classpath.add(new File(path));
            }
            List<String> javacOptions = Arrays.asList("-implicit:none");
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
            fileManager.setLocation(StandardLocation.SOURCE_PATH, Collections.emptyList());
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, Collections.singleton(generatedClassesDirFile));
            fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(classesDirFile));

            // put all j2clClasspath items into a list, we'll copy each time and add generated js
            List<String> baseJ2clArgs = new ArrayList<>(Arrays.asList("-cp", bytecodeClasspath, "-d", intermediateJsPath));
            if (declareLegacyNamespaces) {
                baseJ2clArgs.add("-declarelegacynamespaces");
            }

            String intermediateJsOutput = outputJsPathDir + "/app.js";
            CompilationLevel compLevel = CompilationLevel.fromString(compilationLevel);
            List<String> baseClosureArgs = new ArrayList<>(Arrays.asList(
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
                getLog().debug("Verify zipPath " + zipPath);
                Preconditions.checkArgument(new File(zipPath).exists() && new File(zipPath).isFile(), "jszip doesn't exist! %s", zipPath);

                baseClosureArgs.add("--jszip");
                baseClosureArgs.add(zipPath);

                // add JS zip file to the input store - no nice digest, since so far we don't support changes to the zip
                persistentInputStore.addInput(zipPath, "0");
            }
            baseClosureArgs.add("--js");
            baseClosureArgs.add(intermediateJsPath + "/**/*.js");//precludes default package

            //pre-transpile all dependency sources to our cache dir, add those cached items to closure args
            List<String> transpiledDependencies = handleDependencies(classpath, baseJ2clArgs, persistentInputStore);
            baseClosureArgs.addAll(transpiledDependencies);

            FileTime lastModified = FileTime.fromMillis(0);
            FileTime lastSuccess = FileTime.fromMillis(0);
            getLog().debug("baseJ2clArgs " + String.join(" ", baseJ2clArgs));
            getLog().debug("baseClosureArgs " + String.join(" ", baseClosureArgs));
            getLog().info("Listening for changes...");
            while (true) {
                // currently polling for changes.
                // block until changes instead? easy to replace with filewatcher, just watch out for java9/osx issues...

                List<FileInfo> modifiedJavaFiles = new ArrayList<>();
                FileTime newerThan = lastModified;
                long pollStarted = System.currentTimeMillis();

                //this isn't quite right - should check for _at least one_ newer than lastModified, and if so, recompile all
                //newer than lastSuccess
                //also, should look for .native.js too, but not collect them
                for (String dir : sourceDir) {
                    Files.find(Paths.get(dir),
                               Integer.MAX_VALUE,
                               (filePath, fileAttr) -> !fileAttr.isDirectory()
                                       && fileAttr.lastModifiedTime().compareTo(newerThan) > 0
                                       && javaMatcher.matches(filePath))
                            .forEach(file -> modifiedJavaFiles.add(FileInfo.create(file.toString(), file.toString())));
                }
                long pollTime = System.currentTimeMillis() - pollStarted;
                // don't replace this until the loop finishes successfully, so we know the last time we started a successful compile
                FileTime nextModifiedIfSuccessful = FileTime.fromMillis(System.currentTimeMillis());

                if (modifiedJavaFiles.isEmpty()) {
                    Thread.sleep(100);
                    continue;
                }

                //collect native files in zip, but only if that file is also present in the changed .java sources
                boolean anyNativeJs = false;
                try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(sourcesNativeZipPath))) {
                    for (String dir : sourceDir) {
                        anyNativeJs |= Files.find(Paths.get(dir), Integer.MAX_VALUE, (path, attrs) -> shouldZip(path, modifiedJavaFiles)).reduce(false, (ignore, file) -> {
                            try {
                                zipOutputStream.putNextEntry(new ZipEntry(Paths.get(dir).toAbsolutePath().relativize(file.toAbsolutePath()).toString()));
                                zipOutputStream.write(Files.readAllBytes(file));
                                zipOutputStream.closeEntry();
                                return true;
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }, (a, b) -> a || b);
                    }
                }

                // blindly copy any JS in sources that aren't a native.js
                // TODO be less "blind" about this, only copy changed files?
                for (String dir : sourceDir) {
                    Files.find(Paths.get(dir), Integer.MAX_VALUE, (path, attrs) -> jsMatcher.matches(path) && !nativeJsMatcher.matches(path))
                            .forEach(path -> {
                                try {
                                    Files.copy(path, Paths.get(Paths.get(dir).toAbsolutePath().relativize(path.toAbsolutePath()).toString()));
                                } catch (IOException e) {
                                    throw new RuntimeException("failed to copy plain js", e);
                                }
                            });
                }

                getLog().info(modifiedJavaFiles.size() + " updated java files");
//            modifiedJavaFiles.forEach(System.out::println);

                // compile java files with javac into classesDir
                Iterable<? extends JavaFileObject> modifiedFileObjects = fileManager.getJavaFileObjectsFromStrings(modifiedJavaFiles.stream().map(FileInfo::sourcePath).collect(Collectors.toList()));
                //TODO pass-non null for "classes" to properly kick apt?
                //TODO consider a different classpath for this tasks, so as to not interfere with everything else?

                long javacStarted = System.currentTimeMillis();
                CompilationTask task = compiler.getTask(null, fileManager, null, javacOptions, null, modifiedFileObjects);
                if (!task.call()) {
                    //error occurred, should have been logged, skip the rest of this loop
                    continue;
                }
                long javacTime = System.currentTimeMillis() - javacStarted;

                // add all modified Java files
                //TODO don't just use all generated classes, but look for changes maybe?

                Files.find(Paths.get(generatedClassesDirFile.getAbsolutePath()),
                           Integer.MAX_VALUE,
                           (filePath, fileAttr) ->
                                   !fileAttr.isDirectory()
                                           && javaMatcher.matches(filePath)
                        /*TODO check modified?*/
                ).forEach(file -> modifiedJavaFiles.add(FileInfo.create(file.toString(), file.toString())));

                // run preprocessor on changed files
                File processed = File.createTempFile("preprocessed", ".srcjar");
                try (FileSystem out = FrontendUtils.initZipOutput(processed.getAbsolutePath(), new Problems())) {
                    JavaPreprocessor.preprocessFiles(modifiedJavaFiles, out, new Problems());
                }

                List<String> j2clArgs = new ArrayList<>(baseJ2clArgs);
                if (anyNativeJs) {
                    j2clArgs.add("-nativesourcepath");
                    j2clArgs.add(sourcesNativeZipPath);
                }
                j2clArgs.add(processed.getAbsolutePath());

                long j2clStarted = System.currentTimeMillis();
                Problems transpileResult = transpile(j2clArgs);

                processed.delete();

                if (transpileResult.reportAndGetExitCode(System.err) != 0) {
                    //print problems
                    continue;
                }
                long j2clTime = System.currentTimeMillis() - j2clStarted;

                // TODO copy the generated .js files, so that we only feed the updated ones the jscomp, stop messing around with args...
                long jscompStarted = System.currentTimeMillis();
                if (!jscomp(baseClosureArgs, persistentInputStore, intermediateJsPath)) {
                    continue;
                }
                long jscompTime = System.currentTimeMillis() - jscompStarted;

                getLog().info("Recompile of " + modifiedJavaFiles.size() + " source classes finished in " + (System.currentTimeMillis() - nextModifiedIfSuccessful.to(TimeUnit.MILLISECONDS)) + "ms");
                getLog().info("poll: " + pollTime + "millis");
                getLog().info("javac: " + javacTime + "millis");
                getLog().info("j2cl: " + j2clTime + "millis");
                getLog().info("jscomp: " + jscompTime + "millis");
                lastModified = nextModifiedIfSuccessful;
            }
        } catch (IOException | InterruptedException | ExecutionException e) {
            getLog().error(e);
            throw new MojoExecutionException(e.getMessage());
        }
    }

    private List<String> handleDependencies(List<File> classpath, List<String> baseJ2clArgs, PersistentInputStore persistentInputStore) throws IOException, InterruptedException, ExecutionException {
        List<String> additionalClosureArgs = new ArrayList<>();
        for (File file : classpath) {
            if (!file.exists()) {
                throw new IllegalStateException(file + " does not exist!");
            }
            //TODO maybe skip certain files that have already been transpiled
            if (file.isDirectory()) {
                continue;//...hacky, but probably just classes dir
            }

            // hash the file, see if we already have one
            String hash = hash(file);
            String jszipOut = jsZipCacheDir + "/" + hash + "-" + file.getName() + ".js.zip";
            File jszipOutFile = new File(jszipOut);
            if (jszipOutFile.exists()) {
                additionalClosureArgs.add("--jszip");
                additionalClosureArgs.add(jszipOut);

                persistentInputStore.addInput(jszipOut, "0");
                continue;//already exists, we'll use it
            }

            // run preprocessor
            File processed = File.createTempFile("preprocessed", ".srcjar");
            try (FileSystem out = FrontendUtils.initZipOutput(processed.getAbsolutePath(), new Problems())) {
                ImmutableList<FileInfo> allSources = FrontendUtils.getAllSources(Collections.singletonList(file.getAbsolutePath()), new Problems()).collect(ImmutableList.toImmutableList());
                if (allSources.isEmpty()) {
                    getLog().info("no sources in file " + file);
                    continue;
                }
                JavaPreprocessor.preprocessFiles(allSources, out, new Problems());
            }

            //TODO javac these first, so we have consistent bytecode, and use that to rebuild the classpath

            List<String> pretranspile = new ArrayList<>(baseJ2clArgs);
            pretranspile.addAll(Arrays.asList("-cp", bytecodeClasspath, "-d", jszipOut, "-nativesourcepath", file.getAbsolutePath(), processed.getAbsolutePath()));
            Problems result = transpile(pretranspile);

            // blindly copy any JS in sources that aren't a native.js
            ZipFile zipInputFile = new ZipFile(file);

            processed.delete();
            if (result.reportAndGetExitCode(System.err) == 0) {
                try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + jszipOutFile.toURI()), Collections.singletonMap("create", "true"))) {
                    for (ZipEntry entry : Collections.list(zipInputFile.entries())) {
                        Path entryPath = Paths.get(entry.getName());
                        if (jsMatcher.matches(entryPath) && !nativeJsMatcher.matches(entryPath)) {
                            try (InputStream inputStream = zipInputFile.getInputStream(entry)) {
                                Path path = fs.getPath(entry.getName()).toAbsolutePath();
                                Files.createDirectories(path.getParent());
                                // using StandardCopyOption.REPLACE_EXISTING seems overly pessimistic, but i can't get it to work without it
                                Files.copy(inputStream, path, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }

                additionalClosureArgs.add("--jszip");
                additionalClosureArgs.add(jszipOut);

                persistentInputStore.addInput(jszipOut, "0");
            } else {
                jszipOutFile.delete();
                // ignoring failure for now, TODO don't!
                // This is actually slightly tricky - we can't cache failure, since the user might stop and fix the classpath
                // and then the next build will work, but on the other hand we don't want to fail building jsinterop-base
                // over and over again either.
                getLog().info("Failed compiling " + file + " to " + jszipOutFile.getName() + ", optionally copy a manual version to the cache to avoid this error");
            }
        }
        return additionalClosureArgs;
    }

    private static String hash(File file) {
        try (FileInputStream stream = new FileInputStream(file)) {
            return DigestUtils.md5Hex(stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Transpiles Java to Js. Should have the same effect as running the main directly, except by running
     * it here we don't System.exit at the end, so the JVM can stay hot.
     */
    private static Problems transpile(List<String> j2clArgs) {
        return J2clTranspiler.transpile(j2clArgs.toArray(new String[0]));
    }

    private static boolean shouldZip(Path path, List<FileInfo> modifiedJavaFiles) {
        return nativeJsMatcher.matches(path);// && matchesChangedJavaFile(path, modifiedJavaFiles);
    }

    private static boolean matchesChangedJavaFile(Path path, List<String> modifiedJavaFiles) {
        String pathString = path.toString();
        String nativeFilePath = pathString.substring(0, pathString.lastIndexOf(NativeJavaScriptFile.NATIVE_EXTENSION));
        return modifiedJavaFiles.stream().anyMatch(javaPath -> javaPath.startsWith(nativeFilePath));
    }

    private static boolean jscomp(List<String> baseClosureArgs, PersistentInputStore persistentInputStore, String updatedJsDirectories) throws IOException {
        // collect all js into one artifact (currently jscomp, but it would be wonderful to not pay quite so much for this...)
        List<String> jscompArgs = new ArrayList<>(baseClosureArgs);

        // Build a new compiler for this run, but share the cached js ASTs
        Compiler jsCompiler = new Compiler(System.err);
        jsCompiler.setPersistentInputStore(persistentInputStore);

        // sanity check args
        CommandLineRunner jscompRunner = new InProcessJsCompRunner(jscompArgs.toArray(new String[0]), jsCompiler);
        if (!jscompRunner.shouldRunCompiler()) {
            return false;
        }

        // for each file in the updated dir
        long timestamp = System.currentTimeMillis();
        Files.find(Paths.get(updatedJsDirectories), Integer.MAX_VALUE, (path, attrs) -> jsMatcher.matches(path)).forEach((Path path) -> {
            // add updated JS file to the input store with timestamp instead of digest for now
            persistentInputStore.addInput(path.toString(), timestamp + "");
        });
        //TODO how do we handle deleted files? If they are truly deleted, nothing should reference them, and the module resolution should shake them out, at only the cost of a little memory?

        jscompRunner.run();

        if (jscompRunner.hasErrors()) {
            return false;
        }
        if (jsCompiler.getModules() != null) {
            // clear out the compiler input for the next goaround
            jsCompiler.resetCompilerInput();
        }
        return true;
    }

    static class InProcessJsCompRunner extends CommandLineRunner {

        private final Compiler compiler;

        InProcessJsCompRunner(String[] args, Compiler compiler) {
            super(args);
            this.compiler = compiler;
            setExitCodeReceiver(ignore -> null);
        }

        @Override
        protected Compiler createCompiler() {
            return compiler;
        }
    }
}
