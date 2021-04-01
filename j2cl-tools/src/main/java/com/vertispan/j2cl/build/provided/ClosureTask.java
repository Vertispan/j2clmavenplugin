package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DependencyOptions;
import com.vertispan.j2cl.build.task.*;
import net.cardosi.mojo.tools.Closure;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(TaskFactory.class)
public class ClosureTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.OPTIMIZED_JS;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, Config config) {
        // collect current project JS sources and runtime deps JS sources
        // TODO filter to just JS and sourcemaps? probably not required unless we also get sources
        //      from the actual input source instead of copying it along each step
        List<Input> jsSources = Stream.concat(
                Stream.of(input(project, OutputTypes.TRANSPILED_JS)),
                scope(project.getDependencies(), Dependency.Scope.RUNTIME)
                .stream()
                .map(inputs(OutputTypes.TRANSPILED_JS))
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

        String sourcemapDirectory = "sources";
        return new FinalOutputTask() {
            @Override
            public void execute(Path outputPath) throws Exception {
                Closure closureCompiler = new Closure();

                File closureOutputDir = outputPath.toFile();

                CompilationLevel compilationLevel = CompilationLevel.fromString(compilationLevelConfig);

                // set up a source directory to build from, and to make sourcemaps work
                // TODO move logic to the "post" phase to decide whether or not to copy the sourcemap dir
                String jsOutputDir = new File(closureOutputDir + "/" + initialScriptFilename).getParent();
                File sources = new File(jsOutputDir, sourcemapDirectory);
//            if (compilationLevel == CompilationLevel.BUNDLE) {
//                if (!config.getSourcemapsEnabled()) {
//                    //TODO warn that sourcemaps are there anyway, we can't disable in bundle modes?
//                }
//                sources = new File(jsOutputDir, sourcemapDirectory);
//            } else {
//                if (config.getSourcemapsEnabled()) {
//                    sources = new File(jsOutputDir, sourcemapDirectory);//write to the same places as in bundle mode
//                } else {
//                    sources = entry
//                }
//
//            }

                Files.createDirectories(Paths.get(closureOutputDir.getAbsolutePath(), initialScriptFilename).getParent());


                Map<String, String> defines = new LinkedHashMap<>(configDefines);

                if (compilationLevel == CompilationLevel.BUNDLE) {
                    defines.putIfAbsent("goog.ENABLE_DEBUG_LOADER", "false");//TODO maybe overwrite instead?
                }

                boolean success = closureCompiler.compile(
                        compilationLevel,
                        dependencyMode,
                        languageOut,
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
                        closureOutputDir + "/" + initialScriptFilename
                );

                if (!success) {
                    throw new IllegalStateException("Closure Compiler failed, check log for details");
                }

            }

            @Override
            public void finish() {

            }
        };
    }
}
