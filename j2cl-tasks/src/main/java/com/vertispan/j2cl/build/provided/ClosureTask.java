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

@AutoService(TaskFactory.class)
public class ClosureTask extends TaskFactory {
    public static final PathMatcher JS_SOURCES = withSuffix(".js");
    public static final PathMatcher NATIVE_JS_SOURCES = withSuffix(".native.js");
    public static final PathMatcher PLAIN_JS_SOURCES = new PathMatcher() {
        @Override
        public boolean matches(Path path) {
            return JS_SOURCES.matches(path) && !NATIVE_JS_SOURCES.matches(path);
        }

        @Override
        public String toString() {
            return "Only non-native JS sources";
        }
    };
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
        List<Input> jsSources = Stream.concat(
                Stream.of(
                        input(project, OutputTypes.TRANSPILED_JS).filter(JS_SOURCES),
                        input(project, OutputTypes.GENERATED_SOURCES).filter(PLAIN_JS_SOURCES),
                        input(project, OutputTypes.INPUT_SOURCES).filter(PLAIN_JS_SOURCES)
                ),
                scope(project.getDependencies(), Dependency.Scope.RUNTIME)
                .stream()
                .flatMap(p -> Stream.of(
                        input(p, OutputTypes.TRANSPILED_JS).filter(JS_SOURCES),
                        input(p, OutputTypes.GENERATED_SOURCES).filter(PLAIN_JS_SOURCES),
                        input(p, OutputTypes.INPUT_SOURCES).filter(PLAIN_JS_SOURCES)
                ))
        ).collect(Collectors.toList());


        // grab configs we plan to use
        String compilationLevelConfig = config.getCompilationLevel();
        String initialScriptFilename = config.getInitialScriptFilename();
        Map<String, String> configDefines = config.getDefines();
        DependencyOptions.DependencyMode dependencyMode = DependencyOptions.DependencyMode.valueOf(config.getDependencyMode());
        List<String> entrypoint = config.getEntrypoint();
        CompilerOptions.LanguageMode languageOut = CompilerOptions.LanguageMode.fromString(config.getLanguageOut());
        //TODO probably kill this, or at least make it work like an import via another task so we detect changes
        Collection<String> externs = config.getExterns();
        boolean checkAssertions = config.getCheckAssertions();
        boolean rewritePolyfills = config.getRewritePolyfills();
        boolean sourcemapsEnabled = config.getSourcemapsEnabled();
        List<File> extraJsZips = config.getExtraJsZips();
        String env = config.getEnv();

        return new FinalOutputTask() {
            @Override
            public void execute(TaskContext context) throws Exception {
                Closure closureCompiler = new Closure(context);

                File closureOutputDir = context.outputPath().toFile();

                CompilationLevel compilationLevel = CompilationLevel.fromString(compilationLevelConfig);

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
                                    .collect(Collectors.toList())
                    );
                } else {
                    // For other modes, we're already asking closure to get work done, let's
                    sources = new File(jsOutputDir, Closure.SOURCES_DIRECTORY_NAME);//write to the same place as in bundle mode
                    js = Closure.mapFromInputs(jsSources);
                }
                if (sources != null) {
                    for (Path path : jsSources.stream().map(Input::getParentPaths).flatMap(Collection::stream).collect(Collectors.toList())) {
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
                        extraJsZips,
                        entrypoint,
                        defines,
                        externs,
                        null,
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
            }
        };
    }
}
