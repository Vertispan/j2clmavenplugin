package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.debugging.sourcemap.SourceMapConsumerV3;
import com.google.debugging.sourcemap.SourceMapGeneratorV3;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerInput;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.jscomp.deps.ClosureBundler;
import com.google.javascript.jscomp.deps.DependencyInfo;
import com.google.javascript.jscomp.deps.ModuleLoader;
import com.google.javascript.jscomp.deps.SortedDependencies;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.transpile.BaseTranspiler;
import com.google.javascript.jscomp.transpile.Transpiler;
import com.vertispan.j2cl.build.task.CachedPath;
import com.vertispan.j2cl.build.task.ChangedCachedPath;
import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Input;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskFactory;
import io.methvin.watcher.hashing.Murmur3F;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Emits a single compilationLevel=BUNDLE for this project only, without any dependencies.
 */
@AutoService(TaskFactory.class)
public class ClosureBundleTask extends TaskFactory {

    public static final String BUNDLE_JS_EXTENSION = ".bundle.js";

    @Override
    public String getOutputType() {
        return OutputTypes.BUNDLED_JS;
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
        final List<Input> js;
        if (project.isJsZip()) {
            js = Collections.singletonList(input(project, OutputTypes.BYTECODE).filter(ClosureTask.PLAIN_JS_SOURCES));
        } else {
            // TODO filter to just JS and sourcemaps? probably not required unless we also get sources
            //      from the actual input source instead of copying it along each step
            js = Stream.of(
                            input(project, OutputTypes.TRANSPILED_JS),
                            input(project, OutputTypes.BYTECODE)
                    )
                    .map(i -> i.filter(ClosureTask.PLAIN_JS_SOURCES))
                    .collect(Collectors.toUnmodifiableList());
        }

        // Consider treating this always as true, since the build doesnt get more costly to be incremental
        boolean incrementalEnabled = config.isIncrementalEnabled();

        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

        return context -> {
            assert Files.isDirectory(context.outputPath());
            File closureOutputDir = context.outputPath().toFile();

            // even though we're already making the file in our own hash dir, we also want to
            // name the file by a hash so it has a unique filename based on its contents
            String fileNameKey = project.getKey().replaceAll("[^\\-_a-zA-Z0-9.]", "-");
            String outputFile = closureOutputDir + "/" + fileNameKey + ".js";

            Path outputFilePath = Paths.get(outputFile);
            if (js.stream().map(Input::getFilesAndHashes).flatMap(Collection::stream).findAny().isEmpty()) {
                // if there are no js sources, write an empty file and exit
                Files.createFile(outputFilePath);
                return;// nothing to do
            }

            List<DependencyInfoAndSource> dependencyInfos = new ArrayList<>();
            Compiler jsCompiler = new Compiler(System.err);//TODO before merge, write this to the log

            if (incrementalEnabled && context.lastSuccessfulOutput().isPresent()) {
                // collect any dep info from disk for existing files
                final Map<String, DependencyInfoAndSource> depInfoMap;
                Path lastOutput = context.lastSuccessfulOutput().get();
                try (InputStream inputStream = Files.newInputStream(lastOutput.resolve("depInfo.json"))) {
                    Type listType = new TypeToken<List<DependencyInfoFormat>>() {
                    }.getType();
                    List<DependencyInfoFormat> deps = gson.fromJson(new BufferedReader(new InputStreamReader(inputStream)), listType);
                    depInfoMap = deps.stream()
                            .map(info -> {
                                        Path p = js.stream().flatMap(jsInput -> jsInput.getParentPaths().stream())
                                                .map(parent -> parent.resolve(info.getName()))
                                                .filter(Files::exists)
                                                .findFirst().get();
                                        return new DependencyInfoAndSource(
                                                p, info,
                                                () -> Files.readString(p));
                                    }
                            )
                            .collect(Collectors.toMap(DependencyInfo::getName, Function.identity()));
                }

                // create new dep info for any added/modified file
                for (Input jsInput : js) {
                    for (ChangedCachedPath change : jsInput.getChanges()) {
                        if (change.changeType() == ChangedCachedPath.ChangeType.REMOVED) {
                            depInfoMap.remove(change.getSourcePath().toString());
                        } else {
                            // ADD or MODIFY
                            Path p = jsInput.getParentPaths().stream()
                                    .map(parent -> parent.resolve(change.getSourcePath()))
                                    .filter(Files::exists)
                                    .findFirst().get();
                            CompilerInput input = new CompilerInput(SourceFile.builder()
                                    .withPath(p)
                                    .withOriginalPath(change.getSourcePath().toString())
                                    .build());
                            input.setCompiler(jsCompiler);
                            depInfoMap.put(
                                    change.getSourcePath().toString(),
                                    new DependencyInfoAndSource(p, input, input::getCode)
                            );
                        }
                    }
                }

                // no need to expand to include other files, since this is only computed locally

                // assign the dep info and sources we have
                dependencyInfos.addAll(depInfoMap.values());
            } else {
                //non-incremental, read everything
                for (Input jsInput : js) {
                    for (CachedPath path : jsInput.getFilesAndHashes()) {
                        Path p = jsInput.getParentPaths().stream()
                                .map(parent -> parent.resolve(path.getSourcePath()))
                                .filter(Files::exists)
                                .findFirst().get();
                        CompilerInput input = new CompilerInput(SourceFile.builder()
                                .withPath(p)
                                .withOriginalPath(path.getSourcePath().toString())
                                .build());
                        input.setCompiler(jsCompiler);

                        dependencyInfos.add(new DependencyInfoAndSource(p, input, input::getCode));
                    }
                }
            }

            // re-sort that full collection
            SortedDependencies<DependencyInfoAndSource> sorter = new SortedDependencies<>(dependencyInfos);


            // TODO optional/stretch-goal find first change in the list, so we can keep old prefix of bundle output

            SourceMapGeneratorV3 sourceMapGenerator = new SourceMapGeneratorV3();
            sourceMapGenerator.setSourceRoot("sources");

            // track hashes as we go along, to name the js and sourcemap files
            Murmur3F jsHash = new Murmur3F();
            Murmur3F sourcemapHash = new Murmur3F();

            // rebundle all (optional: remaining) files using this already handled sort
            ClosureBundler bundler = new ClosureBundler(Transpiler.NULL, new BaseTranspiler(
                    new BaseTranspiler.CompilerSupplier(
                            CompilerOptions.LanguageMode.ECMASCRIPT_NEXT.toFeatureSet().without(FeatureSet.Feature.MODULES),
                            ModuleLoader.ResolutionMode.BROWSER,
                            ImmutableList.copyOf(js.stream()
                                    .map(Input::getParentPaths)
                                    .flatMap(Collection::stream)
                                    .map(Path::toString)
                                    .collect(Collectors.toUnmodifiableList())),
                            ImmutableMap.of()
                    ),
                    ""
            )).useEval(false);

            final String sourcemapOutFileName;

            try (OutputStream outputStream = Files.newOutputStream(outputFilePath);
                 BufferedWriter bundleOut = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
                 LineCountingWriter writer = new LineCountingWriter(bundleOut)) {
                for (DependencyInfoAndSource info : sorter.getSortedList()) {
                    String code = info.getSource();
                    String name = info.getName();
                    String sourcemapContents = info.loadSourcemap();

                    //TODO do we actually need this?
                    if (Compiler.isFillFileName(name) && code.isEmpty()) {
                        continue;
                    }

                    jsHash.update(code.getBytes(StandardCharsets.UTF_8));

                    // Append a note indicating the name of the JS file that will follow
                    writer.append("//").append(name).append("\n");

                    // Immediately before appending the JS file's contents, check which line we're starting at, and
                    // merge sourcemap contents
                    if (sourcemapContents != null) {
                        sourcemapHash.update(sourcemapContents.getBytes(StandardCharsets.UTF_8));
                        sourceMapGenerator.setStartingPosition(writer.getLine(), 0);
                        SourceMapConsumerV3 section = new SourceMapConsumerV3();
                        section.parse(sourcemapContents);
                        section.visitMappings((sourceName, symbolName, sourceStartPosition, startPosition, endPosition) -> sourceMapGenerator.addMapping(Paths.get(name).resolveSibling(sourceName).toString(), symbolName, sourceStartPosition, startPosition, endPosition));
                        for (String source : section.getOriginalSources()) {
                            String content = Files.readString(info.getAbsolutePath().resolveSibling(source));
                            sourcemapHash.update(content.getBytes(StandardCharsets.UTF_8));
                            sourceMapGenerator.addSourcesContent(Paths.get(name).resolveSibling(source).toString(), content);
                        }
                    }

                    // Append the current file
                    bundler.withPath(name).appendTo(writer, info, code);
                    writer.append("\n");
                }

                // write a reference to our new sourcemaps
                sourcemapOutFileName = fileNameKey + "-" + sourcemapHash.getValueHexString() + ".bundle.js.map";
                writer.append("//# sourceMappingURL=").append(sourcemapOutFileName).append('\n');
            }

            // TODO hash in the name
            try (OutputStream outputStream = Files.newOutputStream(outputFilePath.resolveSibling(sourcemapOutFileName));
                 BufferedWriter smOut = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                sourceMapGenerator.appendTo(smOut, fileNameKey);
            }

            // append dependency info to deserialize on some incremental rebuild
            try (OutputStream outputStream = Files.newOutputStream(context.outputPath().resolve("depInfo.json"));
                 BufferedWriter jsonOut = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                List<DependencyInfoFormat> jsonList = sorter.getSortedList().stream()
                        .map(DependencyInfoFormat::new)
                        .collect(Collectors.toUnmodifiableList());
                gson.toJson(jsonList, jsonOut);
            }

            Files.move(outputFilePath, outputFilePath.resolveSibling(fileNameKey + "-" + jsHash.getValueHexString() + BUNDLE_JS_EXTENSION));
            //TODO when back to keyboard rename sourcemap? is that a thing we need to do?
        };
    }


    public static class LineCountingWriter extends FilterWriter {
        private int line;
        protected LineCountingWriter(Writer out) {
            super(out);
        }

        public int getLine() {
            return line;
        }

        @Override
        public void write(int c) throws IOException {
            if (c == '\n') {
                line++;
            }
            super.write(c);
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            for (char c : cbuf) {
                if (c == '\n') {
                    line++;
                }
            }
            super.write(cbuf, off, len);
        }

        @Override
        public void write(String str, int off, int len) throws IOException {
            str.chars().skip(off).limit(len).forEach(c -> {
                if (c == '\n') {
                    line++;
                }
            });
            super.write(str, off, len);
        }

        @Override
        public void write(char[] cbuf) throws IOException {
            for (char c : cbuf) {
                if (c == '\n') {
                    line++;
                }
            }
            super.write(cbuf);
        }
    }

    public interface SourceSupplier {
        String get() throws IOException;
    }
    public static class DependencyInfoAndSource implements DependencyInfo {
        private final Path absolutePath;
        private final DependencyInfo delegate;
        private final SourceSupplier sourceSupplier;

        public DependencyInfoAndSource(Path absolutePath, DependencyInfo delegate, SourceSupplier sourceSupplier) {
            this.absolutePath = absolutePath;
            this.delegate = delegate;
            this.sourceSupplier = sourceSupplier;
        }

        public Path getAbsolutePath() {
            return absolutePath;
        }

        public String getSource() throws IOException {
            return sourceSupplier.get();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getPathRelativeToClosureBase() {
            return delegate.getPathRelativeToClosureBase();
        }

        @Override
        public ImmutableList<String> getProvides() {
            return delegate.getProvides();
        }

        @Override
        public ImmutableList<Require> getRequires() {
            return delegate.getRequires();
        }

        @Override
        public ImmutableList<String> getRequiredSymbols() {
            //deliberately overriding the base impl
            return delegate.getRequiredSymbols();
        }

        @Override
        public ImmutableList<String> getTypeRequires() {
            return delegate.getTypeRequires();
        }

        @Override
        public ImmutableMap<String, String> getLoadFlags() {
            return delegate.getLoadFlags();
        }

        @Override
        public boolean isEs6Module() {
            return delegate.isEs6Module();
        }

        @Override
        public boolean isGoogModule() {
            return delegate.isGoogModule();
        }

        @Override
        public boolean getHasExternsAnnotation() {
            return delegate.getHasExternsAnnotation();
        }

        @Override
        public boolean getHasNoCompileAnnotation() {
            return delegate.getHasNoCompileAnnotation();
        }

        public String loadSourcemap() throws IOException {
            String sourceMappingUrlMarker = "//# sourceMappingURL=";
            int offset = getSource().lastIndexOf(sourceMappingUrlMarker);
            if (offset == -1) {
                return null;
            }
            int urlPos = offset + sourceMappingUrlMarker.length();
            String sourcemapName = getSource().substring(urlPos).split("\\s")[0];
            return Files.readString(absolutePath.resolveSibling(sourcemapName));
        }
    }

    public static class DependencyInfoFormat implements DependencyInfo {
        private String name;
//        private String pathRelativeToClosureBase = name;
        private List<String> provides;
//        private List<RequireFormat> requires; //skipping requires as it isnt used by the dep sorter
        private List<String> requiredSymbols;
        private List<String> typeRequires;
        private Map<String, String> loadFlags;
        private boolean hasExternsAnnotation;
        private boolean hasNoCompileAnnotation;

        public DependencyInfoFormat() {

        }

        public DependencyInfoFormat(DependencyInfo info) {
            setName(info.getName());
            setHasExternsAnnotation(info.getHasExternsAnnotation());
            setHasNoCompileAnnotation(info.getHasExternsAnnotation());
            setProvides(info.getProvides());
            setLoadFlags(info.getLoadFlags());
            setTypeRequires(info.getTypeRequires());
            setRequiredSymbols(info.getRequiredSymbols());
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String getPathRelativeToClosureBase() {
            return getName();
        }

        public ImmutableList<String> getProvides() {
            return ImmutableList.copyOf(provides);
        }

        public void setProvides(List<String> provides) {
            this.provides = provides;
        }

        public ImmutableList<Require> getRequires() {
            return ImmutableList.of();
        }

        @Override
        public ImmutableList<String> getRequiredSymbols() {
            return ImmutableList.copyOf(requiredSymbols);
        }

        public void setRequiredSymbols(List<String> requiredSymbols) {
            this.requiredSymbols = requiredSymbols;
        }

        public ImmutableList<String> getTypeRequires() {
            return ImmutableList.copyOf(typeRequires);
        }

        public void setTypeRequires(List<String> typeRequires) {
            this.typeRequires = typeRequires;
        }

        public ImmutableMap<String, String> getLoadFlags() {
            return ImmutableMap.copyOf(loadFlags);
        }

        public void setLoadFlags(Map<String, String> loadFlags) {
            this.loadFlags = loadFlags;
        }

        public boolean getHasExternsAnnotation() {
            return hasExternsAnnotation;
        }

        public void setHasExternsAnnotation(boolean hasExternsAnnotation) {
            this.hasExternsAnnotation = hasExternsAnnotation;
        }

        public boolean getHasNoCompileAnnotation() {
            return hasNoCompileAnnotation;
        }

        public void setHasNoCompileAnnotation(boolean hasNoCompileAnnotation) {
            this.hasNoCompileAnnotation = hasNoCompileAnnotation;
        }
    }
}
