package net.cardosi.mojo.builder;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.FrontendUtils;
import com.google.j2cl.common.Problems;
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
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One-time compiler.
 * <p>
 * For single or stand-alone usage:
 * do invoke SingleCompiler.run(Gwt3Options)
 * <p>
 * For multiple/external usage
 * do invoke SingleCompiler.setup(Gwt3Options) once, and then
 * SingleCompiler.preCompile(FileTime) to execute a single compilation
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
    private static Map<String, MavenProject> baseDirProjectMap;
    private static Set<FrontendUtils.FileInfo> toRecompile = new HashSet<>(); // Using Set to avoid duplication

    public static void run(Gwt3Options options, List<File> orderedClasspath, File targetPath, Map<String, MavenProject> baseDirProjectMap) throws Exception {
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("Setup");
        setup(options, orderedClasspath, targetPath, baseDirProjectMap);
        LOGGER.info("Do compilation");
        List<FrontendUtils.FileInfo> modifiedJavaFiles = getModifiedJavaFiles(FileTime.fromMillis(0));
        try {
            preCompile(modifiedJavaFiles, targetPath);
        } catch (Exception e) {
            LOGGER.severe(e.getMessage());
        }
    }

    public static void setup(Gwt3Options options, List<File> orderedClasspath, File targetPath, Map<String, MavenProject> baseDirProjectMap) throws Exception {
        SingleCompiler.options = options;
        SingleCompiler.baseDirProjectMap = baseDirProjectMap;
        LOGGER.setLevel(Level.INFO);
        intermediateJsPath = options.getIntermediateJsPath();
        LOGGER.info("intermediate js from j2cl path " + intermediateJsPath);
        generatedClassesPath = createTempDir(targetPath);//TODO allow this to be configurable
        LOGGER.info("generated source path " + generatedClassesPath);

        File classesDirFile = options.getClassesDir();
        LOGGER.info("output class directory " + classesDirFile);
        options.getBytecodeClasspath().add(classesDirFile.getAbsolutePath());
        for (String path : options.getBytecodeClasspath()) {
            orderedClasspath.add(0, new File(path));
        }

        javac = new Javac(generatedClassesPath, orderedClasspath, classesDirFile, options.getBootstrapClasspath());

        // put all j2clClasspath items into a list, we'll copy each time and add generated js
        baseJ2clArgs = J2clTranspilerOptions.newBuilder()
                .setClasspaths(options.getBytecodeClasspath())
                .setOutput(Paths.get(intermediateJsPath))
//                .setDeclareLegacyNamespace(options.isDeclareLegacyNamespaces())
                .setSources(Collections.emptyList())
                .setNativeSources(Collections.emptyList())
                .setEmitReadableLibraryInfo(false)
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
        List<String> transpiledDependencies = progressivelyHandleDependencies(orderedClasspath, baseJ2clArgs, persistentInputStore, options.getBytecodeClasspath(), targetPath);
        baseClosureArgs.addAll(transpiledDependencies);
    }

    public static void preCompile(List<FrontendUtils.FileInfo> modifiedJavaFiles, File tempDir) throws Exception {
        // TODO Do that on a per-module basis
        LOGGER.setLevel(Level.INFO);
        final List<FrontendUtils.FileInfo> allSourcesToRecompile = getAllSourcesToRecompile(modifiedJavaFiles);
        //
        File processedZip = preProcessing(allSourcesToRecompile, tempDir);
        //
        compiling(allSourcesToRecompile);
        //
        List<FrontendUtils.FileInfo> nativeSources = getNativeSources(allSourcesToRecompile);
        //
        copyJs(allSourcesToRecompile);
        //
        addGeneratedSources(allSourcesToRecompile);
        //
        J2clTranspilerOptions.Builder j2clArgs = getBuilder(nativeSources, processedZip);
        //
        transpile(j2clArgs, processedZip, allSourcesToRecompile);
    }

    public static void closure() throws IOException {
        // TODO Store/cache results of previous methods to reuse in next one
        // TODO Move to a specific method so that it is called only when the above are successfully run over the original modified sources and depndent ones
        long jscompStarted = System.currentTimeMillis();
        if (!jscomp(baseClosureArgs, persistentInputStore, intermediateJsPath)) {
            return;
        }
        long jscompTime = System.currentTimeMillis() - jscompStarted;
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
     * Preprocess all given sources
     * @param allSourcesToRecompile
     * @param tempDir
     * @throws IOException
     */
    private static File preProcessing(final List<FrontendUtils.FileInfo> allSourcesToRecompile, File tempDir) throws IOException {
        LOGGER.info("preProcessing");
        // run preprocessor on changed files
        long startTime = System.currentTimeMillis();
        File toReturn = File.createTempFile("preprocessed", ".srcjar", tempDir);
        try (FileSystem out = FrontendUtils.initZipOutput(toReturn.getAbsolutePath(), new Problems())) {
            JavaPreprocessor.preprocessFiles(allSourcesToRecompile, out.getPath("/"), new Problems());
        }
        long endTime = System.currentTimeMillis() - startTime;
        LOGGER.info("preprocess: " + endTime + "millis");
        return toReturn;
    }

    /**
     * Compile all given sources
     * @param allSourcesToRecompile
     * @throws RuntimeException
     */
    private static void compiling(final List<FrontendUtils.FileInfo> allSourcesToRecompile) throws RuntimeException {
        LOGGER.info("Java compiling");
        long startTime = System.currentTimeMillis();
        if (!javac.compile(allSourcesToRecompile)) {
            // Store files to recompile next attempt
            toRecompile.addAll(allSourcesToRecompile);
            //error occurred, should have been logged, skip the rest of this loop
            throw new RuntimeException("Failed to compile " + allSourcesToRecompile.size() + " files");
        }
        long endTime = System.currentTimeMillis() - startTime;
        LOGGER.info("javac: " + endTime + "millis");
    }

    /**
     * Retrieve native sources
     * @param allSourcesToRecompile
     * @return
     * @throws IOException
     */
    private static List<FrontendUtils.FileInfo> getNativeSources(final List<FrontendUtils.FileInfo> allSourcesToRecompile) throws IOException {
        LOGGER.info("getNativeSources");
        long startTime = System.currentTimeMillis();
        // collect native js files that we'll pass in a list to the transpiler.
        List<FrontendUtils.FileInfo> toReturn = new ArrayList<>();
        for (String dir : options.getSourceDir()) {
            Files.find(Paths.get(dir), Integer.MAX_VALUE, (path, attrs) -> shouldZip(path, allSourcesToRecompile)).forEach(file -> {
                toReturn.add(FrontendUtils.FileInfo.create(file.toString(), Paths.get(dir).toAbsolutePath().relativize(file.toAbsolutePath()).toString()));
            });
        }
        long endTime = System.currentTimeMillis() - startTime;
        LOGGER.info("getNativeSources: " + endTime + "millis");
        return toReturn;
    }

    /**
     * Copy js files
     * @param allSourcesToRecompile
     * @throws IOException
     */
    private static void copyJs(final List<FrontendUtils.FileInfo> allSourcesToRecompile) throws IOException {
        LOGGER.info("copyJs");
        long startTime = System.currentTimeMillis();
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
                            // Store files to recompile next attempt
                            toRecompile.addAll(allSourcesToRecompile);
                            throw new RuntimeException("failed to copy plain js", e);
                        }
                    });
        }
        long endTime = System.currentTimeMillis() - startTime;
        LOGGER.info("copyJs: " + endTime + "millis");
    }

    /**
     * Add generated sources to given ones
     * @param allSourcesToRecompile
     * @throws IOException
     */
    private static void addGeneratedSources(final List<FrontendUtils.FileInfo> allSourcesToRecompile) throws IOException {
        LOGGER.info("addGeneratedSources");
        long startTime = System.currentTimeMillis();
        // add all modified Java files
        //TODO don't just use all generated classes, but look for changes maybe?
        Files.find(Paths.get(generatedClassesPath.getAbsolutePath()),
                   Integer.MAX_VALUE,
                   (filePath, fileAttr) ->
                           !fileAttr.isDirectory()
                                   && javaMatcher.matches(filePath)
                /*TODO check modified?*/
        ).forEach(file -> allSourcesToRecompile.add(FrontendUtils.FileInfo.create(file.toString(), file.toString())));
        long endTime = System.currentTimeMillis() - startTime;
        LOGGER.info("addGeneratedSources: " + endTime + "millis");
    }

    /**
     * Retrieves the <code>J2clTranspilerOptions.Builder</code>
     * @param nativeSources
     * @param processedZip
     * @return
     */
    private static J2clTranspilerOptions.Builder getBuilder(final List<FrontendUtils.FileInfo> nativeSources, File processedZip) {
        LOGGER.info("getBuilder");
        long startTime = System.currentTimeMillis();
        J2clTranspilerOptions.Builder toReturn = baseJ2clArgs.build().toBuilder();
        if (!nativeSources.isEmpty()) {
            toReturn.setNativeSources(nativeSources);
        }
        List<FrontendUtils.FileInfo> processedJavaFiles = FrontendUtils.getAllSources(Collections.singletonList(processedZip.getAbsolutePath()), new Problems())
                .filter(f -> f.sourcePath().endsWith(".java"))
                .collect(Collectors.toList());
        toReturn.setSources(processedJavaFiles);
        long endTime = System.currentTimeMillis() - startTime;
        LOGGER.info("getBuilder: " + endTime + "millis");
        return toReturn;
    }

    /**
     * Do transpilation
     * @param builder
     * @param processedZip
     * @param allSourcesToRecompile
     */
    private static void transpile(J2clTranspilerOptions.Builder builder, File processedZip, final List<FrontendUtils.FileInfo> allSourcesToRecompile) {
        LOGGER.info("transpile");
        long startTime = System.currentTimeMillis();
        Problems transpileResult = transpile(builder.build());
        processedZip.delete();
        if (transpileResult.reportAndGetExitCode(System.err) != 0) {
            String errors = String.join(", ", transpileResult.getErrors());
            String errorMessage = "Error while transpiling: " + errors;
            LOGGER.severe(errorMessage);
            // Store files to recompile next attempt
            toRecompile.addAll(allSourcesToRecompile);
            throw new RuntimeException(errorMessage);
        }
        long endTime = System.currentTimeMillis() - startTime;
        LOGGER.info("transpile: " + endTime + "millis");
    }

    /**
     * This method retrieves <b>all</b> the java sources to recompile, discovering ones in the same modules and the others in the dependent modules
     * @param modifiedJavaFiles
     * @return
     */
    private static List<FrontendUtils.FileInfo> getAllSourcesToRecompile(List<FrontendUtils.FileInfo> modifiedJavaFiles) {
        // Using Set to avoid duplicate
        final Set<MavenProject> directlyModifiedMavenProjects = retrieveDirectlyModifiedMavenProjects(modifiedJavaFiles);
        Set<MavenProject> mavenProjectsToRecompile = new HashSet<>(directlyModifiedMavenProjects);
        directlyModifiedMavenProjects.forEach(modifiedProject -> recursivelyPopulateDownStreamProjects(modifiedProject, mavenProjectsToRecompile));
        Set<FrontendUtils.FileInfo> toReturn = new HashSet<>(); // Using Set
        mavenProjectsToRecompile.forEach(mavenProject -> populateAllSourcesInMavenProject(mavenProject, toReturn));
        toReturn.addAll(toRecompile);
        toRecompile.clear();
        return new ArrayList<>(toReturn); // returning List because it is the expected class by other methods
    }

    /**
     * Retrieves the <code>MavenProject</code>s the given <b>modifiedJavaFiles</b> belongs to
     * @param modifiedJavaFiles
     */
    private static Set<MavenProject> retrieveDirectlyModifiedMavenProjects(List<FrontendUtils.FileInfo> modifiedJavaFiles) {
        return baseDirProjectMap.keySet().stream()
                .filter(baseDir -> modifiedJavaFiles.stream().anyMatch(fileInfo -> fileInfo.sourcePath().startsWith(baseDir)))
                .map(baseDir -> baseDirProjectMap.get(baseDir))
                .collect(Collectors.toSet());
    }

    /**
     * Add all the sources in the given <code>MavenProject</code>
     * @param modifiedProject
     * @param toPopulate
     */
    private static void populateAllSourcesInMavenProject(MavenProject modifiedProject, Set<FrontendUtils.FileInfo> toPopulate) {
        modifiedProject.getCompileSourceRoots().forEach(sourcePath -> {
            try {
                Files.find(Paths.get(sourcePath),
                           Integer.MAX_VALUE,
                           (filePath, fileAttr) -> !fileAttr.isDirectory()
                                   && javaMatcher.matches(filePath))
                        .forEach(file -> toPopulate.add(FrontendUtils.FileInfo.create(file.toString(), file.toString())));
            } catch (IOException e) {
                LOGGER.severe(e.getMessage());
            }
        });
    }

    /**
     * Recursively retrieves all the dependency tree
     * @param modifiedProject
     * @param toPopulate
     */
    private static void recursivelyPopulateDownStreamProjects(MavenProject modifiedProject, Set<MavenProject> toPopulate) {
        baseDirProjectMap.values().forEach(mavenProject -> mavenProject.getArtifacts().forEach(artifact -> {
            if (artifact.equals(modifiedProject.getArtifact())) {
                toPopulate.add(mavenProject);
                recursivelyPopulateDownStreamProjects(mavenProject, toPopulate);
            }
        }));
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
    private static List<String> progressivelyHandleDependencies(List<File> toTranspile, J2clTranspilerOptions.Builder baseJ2clArgs, PersistentInputStore persistentInputStore, List<String> originalClassPath, File tempDir) throws IOException {
        List<String> toReturn = new ArrayList<>();
        for (int i = originalClassPath.size(); i < toTranspile.size(); i++) {
            File toHandle = toTranspile.get(i);
            List<String> newClasspath = new ArrayList<>(originalClassPath);
            if (isToTranspile(toHandle)) {
                populateHandleDependencies(toHandle, baseJ2clArgs, persistentInputStore, toReturn, tempDir);
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

    private static void populateHandleDependencies(File toHandle, J2clTranspilerOptions.Builder baseJ2clArgs, PersistentInputStore persistentInputStore, List<String> toPopulate, File tempDir) throws IOException {
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
        File processed = File.createTempFile("preprocessed", ".srcjar", tempDir);
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
        // in theory, we only preCompile with the dependencies for this particular dep
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

    private static File createTempDir(File baseDir) {
        int TEMP_DIR_ATTEMPTS = 10000;
        String baseName = System.currentTimeMillis() + "-";
        for (int counter = 0; counter < TEMP_DIR_ATTEMPTS; counter++) {
            File tempDir = new File(baseDir, baseName + counter);
            if (tempDir.mkdir()) {
                return tempDir;
            }
        }
        throw new IllegalStateException(
                "Failed to create directory within "
                        + TEMP_DIR_ATTEMPTS
                        + " attempts (tried "
                        + baseName
                        + "0 to "
                        + baseName
                        + (TEMP_DIR_ATTEMPTS - 1)
                        + ')');
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
