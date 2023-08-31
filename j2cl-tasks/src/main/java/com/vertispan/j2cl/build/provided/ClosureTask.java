package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DependencyOptions;
import com.vertispan.j2cl.build.task.*;
import com.vertispan.j2cl.tools.Closure;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@AutoService(TaskFactory.class)
public class ClosureTask extends TaskFactory {
    private static final Path META_INF = Paths.get("META-INF");
    /** servlet 3 and webjars convention */
    private static final Path META_INF_RESOURCES = META_INF.resolve("resources");
    /** optional directory to offer externs within a jar */
    private static final Path META_INF_EXTERNS = META_INF.resolve("externs");

    private static final Path PUBLIC = Paths.get("public");

    private static final PathMatcher JS_SOURCES = withSuffix(".js");

    private static final PathMatcher XTB = withSuffix(".xtb");
    private static final PathMatcher NATIVE_JS_SOURCES = withSuffix(".native.js");
    private static final PathMatcher EXTERNS_SOURCES = withSuffix(".externs.js");

    private static final PathMatcher IN_META_INF = path -> path.startsWith(META_INF);
    private static final PathMatcher IN_META_INF_EXTERNS = path -> path.startsWith(META_INF_EXTERNS);
    private static final PathMatcher IN_META_INF_RESOURCES = path -> path.startsWith(META_INF_RESOURCES);

    private static final PathMatcher IN_PUBLIC = path -> StreamSupport.stream(path.spliterator(), false).anyMatch(PUBLIC::equals);

    /**
     * JS files that closure should use as type information
     */
    public static final PathMatcher EXTERNS = new PathMatcher() {
        @Override
        public boolean matches(Path path) {
            return IN_META_INF_EXTERNS.matches(path) || EXTERNS_SOURCES.matches(path);
        }

        @Override
        public String toString() {
            return "externs to pass to closure";
        }
    };

    /**
     * JS files that closure should accept as input to bundle/compile.
     */
    public static final PathMatcher PLAIN_JS_SOURCES = new PathMatcher() {
        @Override
        public boolean matches(Path path) {
            if (IN_META_INF.matches(path) && !IN_META_INF_EXTERNS.matches(path)) {
                return false;
            }
            if (IN_PUBLIC.matches(path)) {
                return false;
            }
            return JS_SOURCES.matches(path) && !NATIVE_JS_SOURCES.matches(path) && !EXTERNS.matches(path);
        }

        @Override
        public String toString() {
            return "Only non-native JS sources";
        }
    };

    /**
     * Files that should be copied to the final output directory.
     */
    public static final PathMatcher COPIED_OUTPUT = new PathMatcher() {
        @Override
        public boolean matches(Path path) {
            return IN_PUBLIC.matches(path) || IN_META_INF_RESOURCES.matches(path);
        }

        @Override
        public String toString() {
            return "Output to copy without transpiling or bundling";
        }
    };

    /** Strips off any prefix and returns an absolute path describing where to copy the file */
    public static void copiedOutputPath(Path outputDirectory, CachedPath fileToCopy) throws IOException {
        Path sourcePath = fileToCopy.getSourcePath();
        final Path outputPath;
        if (IN_META_INF_RESOURCES.matches(sourcePath)) {
            outputPath = META_INF_RESOURCES.relativize(sourcePath);
        } else if (IN_PUBLIC.matches(sourcePath)) {
            List<String> dir = new ArrayList<>();
            boolean seenPublic = false;
            for (Path path : sourcePath) {
                if (!seenPublic) {
                    if (path.equals(PUBLIC)) {
                        seenPublic = true;
                    }
                    continue;
                }
                dir.add(path.toString());
            }

            outputPath = Paths.get(dir.remove(0), dir.toArray(new String[0]));
        } else {
            throw new IllegalStateException("Output file not in public/ or META-INF/resources/: " + fileToCopy);
        }
        Path outputFile = outputDirectory.resolve(outputPath);
        Files.createDirectories(outputFile.getParent());
        Files.copy(fileToCopy.getAbsolutePath(), outputFile, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public String getOutputType() {
        return OutputTypes.OPTIMIZED_JS;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public Task resolve(Project project, Config config) {
        // collect current project JS sources and runtime deps JS sources
        // TODO filter to just JS and sourcemaps? probably not required unless we also get sources
        //      from the actual input source instead of copying it along each step
        Stream<Input> jsFromJavaProjects = Stream.concat(
                        Stream.of(project),
                        scope(project.getDependencies(), Dependency.Scope.RUNTIME)
                                .stream()
                                .filter(p -> !p.isJsZip())
                )
                .flatMap(p -> Stream.of(
                        input(p, OutputTypes.TRANSPILED_JS),
                        // Bytecode sources will include original input sources
                        // as well as generated input when the jar was built
                        input(p, OutputTypes.BYTECODE)
                ));

        Stream<Input> jsFromJsZips = scope(project.getDependencies(), Dependency.Scope.RUNTIME)
                .stream()
                .filter(Project::isJsZip)
                .map(p -> input(p, OutputTypes.BYTECODE));

        List<Input> jsSources = Stream.concat(jsFromJavaProjects, jsFromJsZips)
                // Only include the JS and externs
                .map(i -> i.filter(PLAIN_JS_SOURCES, EXTERNS))
                .collect(Collectors.toUnmodifiableList());

        List<Input> outputToCopy = Stream.concat(
                Stream.of(project),
                scope(project.getDependencies(), Dependency.Scope.RUNTIME).stream()
        )
                // Only need to consider the original inputs and generated sources,
                // J2CL won't contribute this kind of sources
                .map(p -> input(p, OutputTypes.BYTECODE).filter(COPIED_OUTPUT))
                .collect(Collectors.toUnmodifiableList());

        // grab configs we plan to use
        CompilationLevel compilationLevel = CompilationLevel.fromString(config.getCompilationLevel());
        if (compilationLevel == null) {
            throw new IllegalArgumentException("Unrecognized compilationLevel: " + config.getCompilationLevel());
        }

        String initialScriptFilename = config.getInitialScriptFilename();
        Map<String, String> configDefines = config.getDefines();
        DependencyOptions.DependencyMode dependencyMode = DependencyOptions.DependencyMode.valueOf(config.getDependencyMode());
        List<String> entrypoint = config.getEntrypoint();
        CompilerOptions.LanguageMode languageOut = CompilerOptions.LanguageMode.fromString(config.getLanguageOut());
        //TODO probably kill this, or at least make it work like an import via another task so we detect changes
        Collection<String> externs = config.getExterns();

        TranslationsFileProcessor translationsFileProcessor = TranslationsFileProcessor.get(config);
        List<Input> xtbInputs = Stream.concat(
                        Stream.of(project),
                        scope(project.getDependencies(), Dependency.Scope.RUNTIME).stream()
                )
                .map(p -> input(p, OutputTypes.BYTECODE))
                // Only include the .xtb
                .map(i -> i.filter(XTB))
                .collect(Collectors.toUnmodifiableList());

        boolean checkAssertions = config.getCheckAssertions();
        boolean rewritePolyfills = config.getRewritePolyfills();
        boolean sourcemapsEnabled = config.getSourcemapsEnabled();
        String env = config.getEnv();

        return new FinalOutputTask() {
            @Override
            public void execute(TaskContext context) throws Exception {
                Closure closureCompiler = new Closure(context);

                File closureOutputDir = context.outputPath().toFile();

                // set up a source directory to build from, and to make sourcemaps work
                // TODO move logic to the "post" phase to decide whether or not to copy the sourcemap dir
                String jsOutputDir = new File(closureOutputDir + "/" + initialScriptFilename).getParent();
                Files.createDirectories(Paths.get(jsOutputDir));
                final File sources;
                final Map<String, List<String>> js;
                if (!sourcemapsEnabled) {
                    // no sourcemaps, we can just reference the JS from their original dirs
                    sources = null;
                    js = Closure.mapFromInputs(jsSources);
                } else if (compilationLevel == CompilationLevel.BUNDLE) {
                    // For BUNDLE+sourcemapsEnabled we have to copy sources, closure must not embed them (better build perf),
                    // and we also specify full paths to the source location
                    sources = new File(jsOutputDir, Closure.SOURCES_DIRECTORY_NAME);
                    js = Collections.singletonMap(
                            sources.getAbsolutePath(),
                            jsSources.stream()
                                    .map(Input::getFilesAndHashes)
                                    .flatMap(Collection::stream)
                                    .map(CachedPath::getSourcePath)
                                    .map(Path::toString)
                                    .collect(Collectors.toUnmodifiableList())
                    );
                } else {
                    // For other modes, we're already asking closure to get work done, let's
                    sources = new File(jsOutputDir, Closure.SOURCES_DIRECTORY_NAME);//write to the same place as in bundle mode
                    js = Closure.mapFromInputs(jsSources);
                }
                if (sources != null) {
                    for (Path path : jsSources.stream().map(Input::getParentPaths).flatMap(Collection::stream).collect(Collectors.toUnmodifiableList())) {
                        FileUtils.copyDirectory(path.toFile(), sources);
                    }
                }

                Map<String, String> defines = new LinkedHashMap<>(configDefines);

                if (compilationLevel == CompilationLevel.BUNDLE) {
                    defines.putIfAbsent("goog.ENABLE_DEBUG_LOADER", "false");//TODO maybe overwrite instead?
                }

                boolean success = closureCompiler.compile(
                        compilationLevel,
                        dependencyMode,
                        languageOut,
                        js,
                        sources,
                        entrypoint,
                        defines,
                        externs,
                        translationsFileProcessor.getTranslationsFile(xtbInputs, context),
                        true,//TODO have this be passed in,
                        checkAssertions,
                        rewritePolyfills,
                        sourcemapsEnabled,
                        env,
                        closureOutputDir + "/" + initialScriptFilename
                );

                if (!success) {
                    throw new IllegalStateException("Closure Compiler failed, check log for details");
                }

            }

            @Override
            public void finish(TaskContext taskContext) throws IOException {
                Path webappDirectory = config.getWebappDirectory();
                if (!Files.exists(webappDirectory)) {
                    Files.createDirectories(webappDirectory);
                }
                FileUtils.copyDirectory(taskContext.outputPath().toFile(), webappDirectory.toFile());
                Path resourceOutputPath = webappDirectory.resolve(initialScriptFilename).getParent();
                for (Input input : outputToCopy) {
                    for (CachedPath entry : input.getFilesAndHashes()) {
                        copiedOutputPath(resourceOutputPath, entry);
                    }
                }
            }
        };
    }
}
