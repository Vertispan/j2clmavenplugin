/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.cardosi.mojo.builder;

import java.io.File;
import java.io.FileInputStream;
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
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.Problems;
import com.google.j2cl.frontend.FrontendUtils;
import com.google.j2cl.generator.NativeJavaScriptFile;
import com.google.j2cl.tools.gwtincompatible.JavaPreprocessor;
import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.j2cl.transpiler.J2clTranspilerOptions;
import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.PersistentInputStore;
import net.cardosi.mojo.options.Gwt3Options;
import net.cardosi.mojo.tools.Javac;
import org.apache.commons.codec.digest.DigestUtils;

import static com.google.common.io.Files.createTempDir;

/**
 * One-time compiler.
 * <p>
 * For single or stand-alone usage:
 * do invoke SingleCompiler.run(Gwt3Options)
 * <p>
 * For multiple/external usage
 * do invoke SingleCompiler.setup(Gwt3Options) once, and then
 * SingleCompiler.compile(FileTime) to execute a single compilation
 */
public class SingleCompiler {

    private static PathMatcher javaMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
    private static PathMatcher jsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.js");
    private static PathMatcher nativeJsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.native.js");

    private final static Logger LOGGER = Logger.getLogger(SingleCompiler.class.getName());

    private static Gwt3Options options;
    private static String intermediateJsPath;
    private static Javac javac;
    private static File generatedClassesPath;
    private static J2clTranspilerOptions.Builder baseJ2clArgs;
    private static List<String> baseClosureArgs;
    private static PersistentInputStore persistentInputStore;

    public static void run(Gwt3Options options, List<File> orderedClasspath) throws IOException, InterruptedException, ExecutionException {
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("Setup");
        setup(options, orderedClasspath);
        LOGGER.info("Do compilation");
        List<FrontendUtils.FileInfo> modifiedJavaFiles = getModifiedJavaFiles(FileTime.fromMillis(0));
        compile(modifiedJavaFiles);
    }

    public static void setup(Gwt3Options options, List<File> orderedClasspath) throws IOException, ExecutionException, InterruptedException {
        SingleCompiler.options = options;
        LOGGER.setLevel(Level.INFO);
        intermediateJsPath = options.getIntermediateJsPath();
        LOGGER.info("intermediate js from j2cl path " + intermediateJsPath);
        generatedClassesPath = createTempDir();//TODO allow this to be configurable
        LOGGER.info("generated source path " + generatedClassesPath);

        File classesDirFile = options.getClassesDir();
        LOGGER.info("output class directory " + classesDirFile);
        options.getBytecodeClasspath().add(classesDirFile.getAbsolutePath());
        for (String path : options.getBytecodeClasspath()) {
            orderedClasspath.add(0, new File(path));
        }

        javac = new Javac(generatedClassesPath, orderedClasspath, classesDirFile);

        // put all j2clClasspath items into a list, we'll copy each time and add generated js
        baseJ2clArgs = J2clTranspilerOptions.newBuilder()
                .setClasspaths(options.getBytecodeClasspath())
                .setOutput(Paths.get(intermediateJsPath))
                .setDeclareLegacyNamespace(options.isDeclareLegacyNamespaces())
                .setSources(Collections.emptyList())
                .setNativeSources(Collections.emptyList())
//                .setEmitReadableLibraryInfo(false)
                .setEmitReadableSourceMap(false)
                .setGenerateKytheIndexingMetadata(false);

        String intermediateJsOutput = options.getJsOutputFile();
        CompilationLevel compilationLevel = CompilationLevel.fromString(options.getCompilationLevel());
        baseClosureArgs = new ArrayList<>(Arrays.asList(
                "--compilation_level", compilationLevel.name(),
                "--js_output_file", intermediateJsOutput,// temp file to write to before we insert the missing line at the top
                "--dependency_mode", options.getDependencyMode().name(),// force STRICT mode so that the compiler at least orders the inputs
                "--language_out", options.getLanguageOut()
        ));
        if (compilationLevel == CompilationLevel.BUNDLE) {
            // support BUNDLE mode, with no remote fetching for dependencies)
            baseClosureArgs.add("--define");
            baseClosureArgs.add("goog.ENABLE_DEBUG_LOADER=false");
        }

        for (String define : options.getDefine()) {
            baseClosureArgs.add("--define");
            baseClosureArgs.add(define);
        }
        for (String entrypoint : options.getEntrypoint()) {
            baseClosureArgs.add("--entry_point");
            baseClosureArgs.add(entrypoint);
        }
        for (String extern : options.getExterns()) {
            baseClosureArgs.add("--externs");
            baseClosureArgs.add(extern);
        }

        // configure a persistent input store - we'll reuse this and not the compiler for now, to cache the ASTs,
        // and still allow jscomp to be in modes other than BUNDLE
        persistentInputStore = new PersistentInputStore();

        for (String zipPath : options.getJ2clClasspath()) {
            Preconditions.checkArgument(new File(zipPath).exists() && new File(zipPath).isFile(), "jszip doesn't exist! %s", zipPath);

            baseClosureArgs.add("--jszip");
            baseClosureArgs.add(zipPath);

            // add JS zip file to the input store - no nice digest, since so far we don't support changes to the zip
            persistentInputStore.addInput(zipPath, "0");
        }
        baseClosureArgs.add("--js");
        baseClosureArgs.add(intermediateJsPath + "/**/*.js");//precludes default package

        //pre-transpile all dependency sources to our cache dir, add those cached items to closure args
        List<String> transpiledDependencies = progressivelyHandleDependencies(orderedClasspath, baseJ2clArgs, persistentInputStore, options.getBytecodeClasspath());
        baseClosureArgs.addAll(transpiledDependencies);
    }

    public static void compile(List<FrontendUtils.FileInfo> modifiedJavaFiles) throws IOException {
        LOGGER.setLevel(Level.INFO);
        // collect native js files that we'll pass in a list to the transpiler.
        List<FrontendUtils.FileInfo> nativeSources = new ArrayList<>();
        for (String dir : options.getSourceDir()) {
            Files.find(Paths.get(dir), Integer.MAX_VALUE, (path, attrs) -> shouldZip(path, modifiedJavaFiles)).forEach(file -> {
                nativeSources.add(FrontendUtils.FileInfo.create(file.toString(), Paths.get(dir).toAbsolutePath().relativize(file.toAbsolutePath()).toString()));
            });
        }

        LOGGER.info(modifiedJavaFiles.size() + " updated java files");
//            modifiedJavaFiles.forEach(System.out::println);

        long javacStarted = System.currentTimeMillis();

        if (!javac.compile(modifiedJavaFiles)) {
            //error occurred, should have been logged, skip the rest of this loop
            return;
        }
        long javacTime = System.currentTimeMillis() - javacStarted;

        // blindly copy any JS in sources that aren't a native.js
        // TODO be less "blind" about this, only copy changed files?
        Iterable<String> dirs = () -> Stream.concat(Stream.of(generatedClassesPath.getAbsolutePath()), options.getSourceDir().stream()).iterator();
        for (String dir : dirs) {
            Files.find(Paths.get(dir), Integer.MAX_VALUE, (path, attrs) -> jsMatcher.matches(path) && !nativeJsMatcher.matches(path))
                    .forEach(path -> {
                        try {
                            final Path target = Paths.get(options.getIntermediateJsPath(), Paths.get(dir).toAbsolutePath().relativize(path.toAbsolutePath()).toString());
                            Files.createDirectories(target.getParent());
                            // using StandardCopyOption.REPLACE_EXISTING seems overly pessimistic, but i can't get it to work without it
                            Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new RuntimeException("failed to copy plain js", e);
                        }
                    });
        }

        // add all modified Java files
        //TODO don't just use all generated classes, but look for changes maybe?

        Files.find(Paths.get(generatedClassesPath.getAbsolutePath()),
                   Integer.MAX_VALUE,
                   (filePath, fileAttr) ->
                           !fileAttr.isDirectory()
                                   && javaMatcher.matches(filePath)
                /*TODO check modified?*/
        ).forEach(file -> modifiedJavaFiles.add(FrontendUtils.FileInfo.create(file.toString(), file.toString())));

        // run preprocessor on changed files
        File processedZip = File.createTempFile("preprocessed", ".srcjar");
        try (FileSystem out = FrontendUtils.initZipOutput(processedZip.getAbsolutePath(), new Problems())) {
            JavaPreprocessor.preprocessFiles(modifiedJavaFiles, out.getPath("/"), new Problems());
        }

        J2clTranspilerOptions.Builder j2clArgs = baseJ2clArgs.build().toBuilder();
        if (!nativeSources.isEmpty()) {
            j2clArgs.setNativeSources(nativeSources);
        }
        List<FrontendUtils.FileInfo> processedJavaFiles = FrontendUtils.getAllSources(Collections.singletonList(processedZip.getAbsolutePath()), new Problems())
                .filter(f -> f.sourcePath().endsWith(".java"))
                .collect(Collectors.toList());
        j2clArgs.setSources(processedJavaFiles);

        long j2clStarted = System.currentTimeMillis();
        Problems transpileResult = transpile(j2clArgs.build());

        processedZip.delete();

        if (transpileResult.reportAndGetExitCode(System.err) != 0) {
            //print problems
            return;
        }
        long j2clTime = System.currentTimeMillis() - j2clStarted;

        // TODO copy the generated .js files, so that we only feed the updated ones the jscomp, stop messing around with args...
        long jscompStarted = System.currentTimeMillis();
        if (!jscomp(baseClosureArgs, persistentInputStore, intermediateJsPath)) {
            return;
        }
        long jscompTime = System.currentTimeMillis() - jscompStarted;

        LOGGER.info("javac: " + javacTime + "millis");
        LOGGER.info("j2cl: " + j2clTime + "millis");
        LOGGER.info("jscomp: " + jscompTime + "millis");
    }

    /**
     * This method returns the list of modified files since a given <code>FileTime</code>
     * @param newerThan
     * @return List of modified files, eventually empty
     * @throws IOException
     */
    protected static List<FrontendUtils.FileInfo> getModifiedJavaFiles(FileTime newerThan) throws IOException {
        List<FrontendUtils.FileInfo> toReturn = new ArrayList<>();
        //this isn't quite right - should check for _at least one_ newer than lastModified, and if so, recompile all
        //newer than lastSuccess
        //also, should look for .native.js too, but not collect them
        for (String dir : options.getSourceDir()) {
            Files.find(Paths.get(dir),
                       Integer.MAX_VALUE,
                       (filePath, fileAttr) -> !fileAttr.isDirectory()
                               && fileAttr.lastModifiedTime().compareTo(newerThan) > 0
                               && javaMatcher.matches(filePath))
                    .forEach(file -> toReturn.add(FrontendUtils.FileInfo.create(file.toString(), file.toString())));
        }
        return toReturn;
    }

    /**
     * This method incrementally transpile the dependencies in the given <code>List</code> classpath, starting from the <b>fixedClassPathElements</b> element (i.e. elements from 0 to fixedClassPathElements -1
     * are always set in the classpath)
     * @param toTranspile
     * @param baseJ2clArgs
     * @param persistentInputStore
     * @param originalClassPath
     * @return
     * @throws IOException
     */
    private static List<String> progressivelyHandleDependencies(List<File> toTranspile, J2clTranspilerOptions.Builder baseJ2clArgs, PersistentInputStore persistentInputStore, List<String> originalClassPath) throws IOException {
        List<String> toReturn = new ArrayList<>();
        for (int i = originalClassPath.size(); i < toTranspile.size(); i++) {
            File toHandle = toTranspile.get(i);
            List<String> newClasspath = new ArrayList<>();
            newClasspath.addAll(originalClassPath);
            if (isToTranspile(toHandle)) {
                populateHandleDependencies(toHandle, baseJ2clArgs, persistentInputStore, toReturn);
                newClasspath.add(toHandle.getAbsolutePath());
                baseJ2clArgs.setClasspaths(newClasspath);
            }
        }
        return toReturn;
    }

    /**
     * Verify if the given <code>File</code> is to be transpiled - i.e. it is changed
     * @param toCheck
     * @return
     */
    private static boolean isToTranspile(File toCheck) {
        if (!toCheck.exists()) {
            throw new IllegalStateException(toCheck + " does not exist!");
        }
        //TODO maybe skip certain files that have already been transpiled
        if (toCheck.isDirectory()) {
            return false;//...hacky, but probably just classes dir
        }
        // TODO Implement actual check
        return true;
    }

    private static void populateHandleDependencies(File toHandle, J2clTranspilerOptions.Builder baseJ2clArgs, PersistentInputStore persistentInputStore, List<String> toPopulate) throws IOException {
        // hash the file, see if we already have one
        String hash = hash(toHandle);
        String jszipOut = options.getJsZipCacheDir() + "/" + hash + "-" + toHandle.getName() + ".js.zip";
        LOGGER.info(toHandle + " will be built to " + jszipOut);
        File jszipOutFile = new File(jszipOut);
        if (jszipOutFile.exists()) {
            toPopulate.add("--jszip");
            toPopulate.add(jszipOut);
            persistentInputStore.addInput(jszipOut, "0");
            return;//already exists, we'll use it
        }
        // run preprocessor
        File processed = File.createTempFile("preprocessed", ".srcjar");
        try (FileSystem out = FrontendUtils.initZipOutput(processed.getAbsolutePath(), new Problems())) {
            ImmutableList<FrontendUtils.FileInfo> allSources = FrontendUtils.getAllSources(Collections.singletonList(toHandle.getAbsolutePath()), new Problems())
                    .filter(f -> f.sourcePath().endsWith(".java"))
                    .collect(ImmutableList.toImmutableList());
            if (allSources.isEmpty()) {
                LOGGER.info("no sources in file " + toHandle);
                return;
            }
            JavaPreprocessor.preprocessFiles(allSources, out.getPath("/"), new Problems());
        }

        //TODO javac these first, so we have consistent bytecode, and use that to rebuild the classpath
        J2clTranspilerOptions.Builder pretranspile = baseJ2clArgs.build().toBuilder();
        // in theory, we only compile with the dependencies for this particular dep
        pretranspile.setOutput(FrontendUtils.initZipOutput(jszipOut, new Problems()).getPath("/"));
        pretranspile.setNativeSources(FrontendUtils.getAllSources(Collections.singletonList(toHandle.getAbsolutePath()), new Problems())
                                              .filter(p -> p.sourcePath().endsWith(".native.js"))
                                              .collect(ImmutableList.toImmutableList()));
        List<FrontendUtils.FileInfo> processedJavaFiles = FrontendUtils.getAllSources(Collections.singletonList(processed.getAbsolutePath()), new Problems())
                .filter(f -> f.sourcePath().endsWith(".java"))
                .collect(ImmutableList.toImmutableList());
        if (processedJavaFiles.isEmpty()) {
            LOGGER.info("no sources left in " + toHandle + " after preprocessing");
            return;
        }
        pretranspile.setSources(processedJavaFiles);
        Problems result = transpile(pretranspile.build());

        // blindly copy any JS in sources that aren't a native.js
        ZipFile zipInputFile = new ZipFile(toHandle);
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
            toPopulate.add("--jszip");
            toPopulate.add(jszipOut);
            persistentInputStore.addInput(jszipOut, "0");
        } else {
            jszipOutFile.delete();
            // ignoring failure for now, TODO don't!
            // This is actually slightly tricky - we can't cache failure, since the user might stop and fix the classpath
            // and then the next build will work, but on the other hand we don't want to fail building jsinterop-base
            // over and over again either.
            LOGGER.info("Failed compiling " + toHandle + " to " + jszipOutFile.getName() + ", optionally copy a manual version to the cache to avoid this error");
        }
    }

    private static String hash(File file) {
        try (FileInputStream stream = new FileInputStream(file)) {
            return DigestUtils.md5Hex(stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean matchesChangedJavaFile(Path path, List<FrontendUtils.FileInfo> modifiedJavaFiles) {
        String pathString = path.toString();
        String nativeFilePath = pathString.substring(0, pathString.lastIndexOf(NativeJavaScriptFile.NATIVE_EXTENSION));
        LOGGER.log(Level.FINE, "nativeFilePath " + nativeFilePath);
        return modifiedJavaFiles.stream().anyMatch(fileInfo -> {
            LOGGER.log(Level.FINE, "fileInfo.sourcePath() " + fileInfo.sourcePath());
            return fileInfo.sourcePath().startsWith(nativeFilePath);
        });
    }

    /**
     * Transpiles Java to Js. Should have the same effect as running the main directly, except by running
     * it here we don't System.exit at the end, so the JVM can stay hot.
     */
    private static Problems transpile(J2clTranspilerOptions j2clArgs) {
        return J2clTranspiler.transpile(j2clArgs);
    }

    private static boolean shouldZip(Path path, List<FrontendUtils.FileInfo> modifiedJavaFiles) {
        return nativeJsMatcher.matches(path) && matchesChangedJavaFile(path, modifiedJavaFiles);
    }

    private static boolean jscomp(List<String> baseClosureArgs, PersistentInputStore persistentInputStore, String updatedJsDirectories) throws IOException {
        // collect all js into one artifact (currently jscomp, but it would be wonderful to not pay quite so much for this...)
        List<String> jscompArgs = new ArrayList<>(baseClosureArgs);

        // BuildMojo a new compiler for this run, but share the cached js ASTs
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
