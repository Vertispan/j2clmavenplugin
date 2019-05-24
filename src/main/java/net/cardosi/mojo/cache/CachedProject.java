package net.cardosi.mojo.cache;

import com.google.j2cl.common.FrontendUtils;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.*;
import net.cardosi.mojo.ClosureBuildConfiguration;
import net.cardosi.mojo.Hash;
import net.cardosi.mojo.tools.GwtIncompatiblePreprocessor;
import net.cardosi.mojo.tools.J2cl;
import net.cardosi.mojo.tools.Javac;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CachedProject {
    private enum Step {
        Hash,
        ProcessAnnotations,
        StripGwtIncompatible,
        GenerateStrippedBytecode,
        TranspileSources,
        AssembleOutput
    }

    private static final PathMatcher javaMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
    private static final PathMatcher jsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.js");
    private static final PathMatcher nativeJsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.native.js");

    private final DiskCache diskCache;
    private Artifact artifact;
    private MavenProject currentProject;
    private List<CachedProject> children;
    private final List<CachedProject> dependents = new ArrayList<>();
    private final List<String> compileSourceRoots;

    private final Map<Step, CompletableFuture<TranspiledCacheEntry>> steps = Collections.synchronizedMap(new EnumMap<>(Step.class));

    private boolean ignoreJavacFailure;
    private Set<ClosureBuildConfiguration> registeredBuildTerminals = new HashSet<>();

    public CachedProject(DiskCache diskCache, Artifact artifact, MavenProject currentProject, List<CachedProject> children, List<String> compileSourceRoots) {
        this.diskCache = diskCache;
        this.compileSourceRoots = compileSourceRoots;
        replace(artifact, currentProject, children);
    }

    public CachedProject(DiskCache diskCache, Artifact artifact, MavenProject currentProject, List<CachedProject> children) {
        this(diskCache, artifact, currentProject, children, currentProject.getCompileSourceRoots());
    }

    public void replace(Artifact artifact, MavenProject currentProject, List<CachedProject> children) {
        assert this.children == null || (this.children.isEmpty() && this.currentProject.getArtifacts().isEmpty());

        this.artifact = artifact;
        this.currentProject = currentProject;
        this.children = children;

        for (CachedProject child : children) {
            child.dependents.add(this);
        }
    }

    /**
     * Indicates that some source has changed and at the very least the hash should be recomputed, which implies that
     * downstream projects too should be updated. Used to forward-propagate changes through the graph, when they get
     * to a root (compiled app or test), that will actually trigger the compilation to take place as needed.
     *
     * TODO this could be updated to compare before/after hash and avoid marking children as dirty
     */
    public void markDirty() {
        synchronized (steps) {
            if (steps.isEmpty()) {
                return;
            }
            for (CompletableFuture<TranspiledCacheEntry> cf : steps.values()) {
                cf.cancel(true);
            }
            steps.clear();
        }

        dependents.forEach(CachedProject::markDirty);

        //TODO cache those "compile me" or "test me" values so we don't pass around like this
        if (!registeredBuildTerminals.isEmpty()) {
            build();
        }
    }

    //TODO instead of these, consider a .test() method instead?
    public MavenProject getMavenProject() {
        return currentProject;
    }
    public List<CachedProject> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public String getArtifactId() {
        return artifact.getArtifactId();
    }

    public String getArtifactKey() {
        return ArtifactUtils.key(artifact);
    }

    public Artifact getArtifact() {
        return artifact;
    }

    @Override
    public String toString() {
        return "CachedProject{" +
                "artifact=" + artifact +
                ", currentProject=" + currentProject +
                ", children=" + children.stream().map(CachedProject::getArtifactKey).collect(Collectors.joining(", ")) +
                ", steps=" + steps +
                '}';
    }

    public boolean hasSourcesMapped() {
        //TODO should eventually support external artifact source dirs so we can watch that instead of using jars
        return !compileSourceRoots.isEmpty();
    }

    public void watch() {

    }

    public boolean isIgnoreJavacFailure() {
        return ignoreJavacFailure;
    }

    public void setIgnoreJavacFailure(boolean ignoreJavacFailure) {
        //TODO com.google.jsinterop:base, and make it configurable for other not-actually-compatible libs
        this.ignoreJavacFailure = ignoreJavacFailure;
    }

    public CompletableFuture<TranspiledCacheEntry> registerAsApp(ClosureBuildConfiguration config) {
        registeredBuildTerminals.add(config);

        // if we're already compiled to this new terminal, then nothing will happen, otherwise we'll build everything
        // that needs building
        return jscompWithScope(config);
    }

    private CompletableFuture<Void> build() {
        return CompletableFuture.allOf(
                registeredBuildTerminals.stream().map(cfg -> jscompWithScope(cfg)).toArray(CompletableFuture[]::new)
        );
    }

    private CompletableFuture<TranspiledCacheEntry> getOrCreate(Step step, Function<TranspiledCacheEntry, CompletableFuture<TranspiledCacheEntry>> instructions) {
//        synchronized (steps) {
//        System.out.println("requested " + getArtifactKey() + " " + step);
        return steps.computeIfAbsent(step, ignore -> {
            if (step == Step.Hash) {
                return instructions.apply(null);
            }
            // first check if it is already on disk (doesn't apply to Hash)
            TranspiledCacheEntry dir = hash().join();
            // try to create the dir - if we succeed, we own the lock, continue
            File stepDir = new File(dir.getCacheDir(), step.name());

            File completeMarker = new File(stepDir, step.name() + "-complete");
            File failedMarker = new File(stepDir, step.name() + "failed");

//            long start = System.currentTimeMillis();
            if (!stepDir.mkdirs()) {
                // wait for complete/failed markers, if either exists, we can bail early
                Path cacheDirPath = Paths.get(dir.getCacheDir().toURI());
                try (WatchService w = cacheDirPath.getFileSystem().newWatchService()) {
                    cacheDirPath.register(w, StandardWatchEventKinds.ENTRY_CREATE);
                    // first check to see if it exists, then wait for next event to occur
                    do {
                        if (completeMarker.exists()) {
//                            System.out.println(getArtifactKey() + " " + step +" ready after " + (System.currentTimeMillis() - start) + "ms of waiting");
                            return CompletableFuture.completedFuture(dir);
                        }
                        if (failedMarker.exists()) {
                            System.out.println("compilation failed in some other thread/process " + getArtifactKey() + " " + dir.getHash());
                            CompletableFuture<TranspiledCacheEntry> failure = new CompletableFuture<>();
                            //TODO read out the actual error from the last attempt
                            failure.completeExceptionally(new IllegalStateException("Step " + step + " failed in some other process/thread " + getArtifactKey() + " " + dir.getHash()));
                            return failure;
                        }

                        // if not, keep waiting and logging
                        // TODO provide a way to timeout and nuke the dir since no one seems to own it
                        System.out.println("Waiting 10s and then checking again if other thread/process finished " + getArtifactKey());
                        w.poll(10, TimeUnit.SECONDS);
                    } while (true);
                } catch (IOException | InterruptedException ex) {
                    System.out.println("Error while waiting for external thread/process");
                    ex.printStackTrace();
                    CompletableFuture<TranspiledCacheEntry> failure = new CompletableFuture<>();
                    failure.completeExceptionally(ex);
                    return failure;
                }
            }

            return instructions.apply(dir).whenComplete((success, failure) -> {
                try {

                    if (failure == null) {
//                        System.out.println("completed " + getArtifactKey() + " " + step + " in " + (System.currentTimeMillis() - start) + "ms of waiting");
                        Files.createFile(completeMarker.toPath());
                    } else {
                        System.out.println("failed " + getArtifactKey() + " " + step);
                        failure.printStackTrace();
                        Files.createFile(failedMarker.toPath());
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                    throw new UncheckedIOException(ex);
                }
            });
        });
//        }
    }

    private <T> CompletableFuture<TranspiledCacheEntry> getOrCreate(Step step, Supplier<T> dependencies, BiFunction<T, TranspiledCacheEntry, TranspiledCacheEntry> work) {
        return getOrCreate(step, entry -> CompletableFuture
                        .supplyAsync(() -> {
//                    System.out.println("starting dependency step for " + getArtifactKey() + " " + step);
                            try {
                                return dependencies.get();
                            } finally {
//                        System.out.println("done with dependency step for " + getArtifactKey() + " " + step);

                            }
                        })
                        .thenApplyAsync(output -> {
//                    System.out.println("starting pool work for " + getArtifactKey() + " " + step);
                            long start = System.currentTimeMillis();
                            try {
                                return work.apply(output, entry);
                            } catch (Throwable t) {
                                t.printStackTrace();
                                throw t;
                            } finally {
                                System.out.println("done with pool work for " + getArtifactKey() + " " + step + " in " + (System.currentTimeMillis() - start) + "ms");
                            }
                        }, diskCache.pool())
        );
    }

    private CompletableFuture<TranspiledCacheEntry> jscompWithScope(ClosureBuildConfiguration config) {
        return getOrCreate(Step.AssembleOutput, () -> {


            // For each child that matches the specified scopes, ask for the compiled js output. As this is the
            // full set of $specifiedScope dependencies for the current project, we actually don't need to j2cl
            // anything else, and just need to generate scope=compile bytecode for each of their dependencies

            return Stream.concat(
                    Stream.of(this),
                    children.stream()
                            .filter(child -> {
                                //if child is in any registeredScope item
                                return new ScopeArtifactFilter(config.getClasspathScope()).include(child.getArtifact());
                            })
            )
                    .map(child -> child.j2cl())
//                    .collect(Collectors.toList()).stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());
        }, (reqs, entry) -> {
            //given these, jscomp everything (this includes ourself)

            PersistentInputStore persistentInputStore = diskCache.getPersistentInputStore();//TODO scope this per app? is that necessary?

            Compiler jsCompiler = new Compiler(System.err);
//            jsCompiler.setPersistentInputStore(persistentInputStore);

            List<String> jscompArgs = new ArrayList<>();
            reqs.stream().map(TranspiledCacheEntry::getTranspiledSourcesDir).map(File::getAbsolutePath).distinct().forEach(dir -> {
                //add this dir as to be compiled
                jscompArgs.add("--js");
                jscompArgs.add(dir + "/**/*.js");

                //TODO copy to somewhere with a consistent path instead so that we don't change the path each recompile,
                //     then restore the persistent input store
            });

            //TODO scope=runtime jszips
            diskCache.getExtraJsZips().forEach(file -> {
                jscompArgs.add("--jszip");
                jscompArgs.add(file.getAbsolutePath());
            });

            for (String entrypoint : config.getEntrypoint()) {
                jscompArgs.add("--entry_point");
                jscompArgs.add(entrypoint);
            }

            CompilationLevel compilationLevel = CompilationLevel.fromString(config.getCompilationLevel());
            if (compilationLevel == CompilationLevel.BUNDLE) {
                jscompArgs.add("--define");
                jscompArgs.add("goog.ENABLE_DEBUG_LOADER=false");
            }

            for (Map.Entry<String, String> define : config.getDefines().entrySet()) {
                jscompArgs.add("--define");
                jscompArgs.add(define.getKey() + "=" + define.getValue());
            }

            jscompArgs.add("--compilation_level");
            jscompArgs.add(compilationLevel.name());

            jscompArgs.add("--dependency_mode");
            jscompArgs.add(DependencyOptions.DependencyMode.PRUNE.name());


            jscompArgs.add("--language_out");
            jscompArgs.add("ECMASCRIPT5");

            new File(config.getWebappDirectory() + "/" + config.getInitialScriptFilename()).getParentFile().mkdirs();
            jscompArgs.add("--js_output_file");
            jscompArgs.add(config.getWebappDirectory() + "/" + config.getInitialScriptFilename());

            for (String extern : config.getExterns()) {
                jscompArgs.add("--externs");
                jscompArgs.add(extern);
            }

            //TODO bundles

            // sanity check args
            jscompArgs.forEach(System.out::println);
            CommandLineRunner jscompRunner = new InProcessJsCompRunner(jscompArgs.toArray(new String[0]), jsCompiler);
            if (!jscompRunner.shouldRunCompiler()) {
                throw new IllegalStateException("Closure Compiler setup error, check log for details");
            }

            //TODO historically we didnt populate the persistent input store until this point, so put it here
            //     if we restore it

            try {
                jscompRunner.run();

                if (jscompRunner.hasErrors()) {
                    throw new IllegalStateException("closure compiler failed, check log for details");
                }
            } finally {
                if (jsCompiler.getModules() != null) {
                    // clear out the compiler input for the next go-around
                    jsCompiler.resetCompilerInput();
                }
            }


            return entry;
        });
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


    private CompletableFuture<TranspiledCacheEntry> j2cl() {
        return getOrCreate(Step.TranspileSources, () -> {

            // collect all scope=compile dependencies and our own stripped sources

            strippedSources().join();
            return children.stream()
                    .filter(child -> {
                        return new ScopeArtifactFilter(Artifact.SCOPE_COMPILE).include(child.getArtifact());
                    })
                    .map(child -> child.strippedBytecode())
//                    .collect(Collectors.toList()).stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());


        }, (bytecodeDeps, entry) -> {
            List<FrontendUtils.FileInfo> sourcesToCompile = getFileInfoInDir(Paths.get(entry.getStrippedSourcesDir().toURI()), javaMatcher);
            if (sourcesToCompile.isEmpty()) {
                return entry;
            }
            //invoke j2cl on these sources, classpath
            List<File> strippedClasspath = new ArrayList<>(bytecodeDeps.stream().map(TranspiledCacheEntry::getStrippedBytecodeDir).collect(Collectors.toList()));
            strippedClasspath.addAll(diskCache.getExtraClasspath());

            J2cl j2cl = new J2cl(strippedClasspath, diskCache.getBootstrap(), entry.getTranspiledSourcesDir());
            List<FrontendUtils.FileInfo> nativeSources;
            if (hasSourcesMapped()) {
                nativeSources = compileSourceRoots.stream().flatMap(dir -> getFileInfoInDir(Paths.get(dir), nativeJsMatcher).stream()).collect(Collectors.toList());
            } else {
                nativeSources = getFileInfoInDir(entry.getUnpackedSources().toPath(), nativeJsMatcher);
            }

            boolean j2clSuccess = j2cl.transpile(sourcesToCompile, nativeSources);
            if (!j2clSuccess) {
                if (!isIgnoreJavacFailure()) {
                    throw new IllegalStateException("j2cl failed, check log for details");
                }
            }

            //copy over other plain js
            Path outSources = entry.getTranspiledSourcesDir().toPath();
            if (hasSourcesMapped()) {
                compileSourceRoots.stream().forEach(dir ->
                        getFileInfoInDir(Paths.get(dir), path -> jsMatcher.matches(path) && !nativeJsMatcher.matches(path))
                                .stream().map(FrontendUtils.FileInfo::sourcePath).map(Paths::get)
                                .map(p -> Paths.get(dir).relativize(p))
                        .forEach(path -> {
                            try {
                                Files.createDirectories(outSources.resolve(path).getParent());
                                Files.copy(Paths.get(dir).resolve(path), outSources.resolve(path));
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                );
            } else {
                getFileInfoInDir(entry.getUnpackedSources().toPath(), path -> jsMatcher.matches(path) && !nativeJsMatcher.matches(path))
                        .stream()
                        .map(FrontendUtils.FileInfo::sourcePath).map(Paths::get)
                        .map(p -> entry.getUnpackedSources().toPath().relativize(p))
                .forEach(path -> {
                    try {
                        Files.createDirectories(outSources.resolve(path).getParent());
                        Files.copy(entry.getUnpackedSources().toPath().resolve(path), outSources.resolve(path));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }

            return entry;
        });
    }

    private CompletableFuture<TranspiledCacheEntry> strippedBytecode() {
        return getOrCreate(Step.GenerateStrippedBytecode, () -> {

            // If there is no sources, or no sources with GwtIncompatible, then just return the original bytecode as-is.
            // This lets us get away with not being able to transpile annotation processors or their dependencies, or
            // artifacts which just include compile-time stuff like annotations.
            //TODO

            // In order to generate bytecode, we strip the sources, and javac that against the stripped bytecode of all
            // scope=compile dependencies

            strippedSources().join();
            return children.stream()
                    .filter(child -> {
                        return new ScopeArtifactFilter(Artifact.SCOPE_COMPILE).include(child.getArtifact());
                    })
                    .map(child -> child.strippedBytecode())
//                    .collect(Collectors.toList()).stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());


        }, (bytecodeDeps, entry) -> {
            //invoke javac on these sources, classpath
            List<File> strippedClasspath = new ArrayList<>(diskCache.getExtraClasspath());
            strippedClasspath.addAll(bytecodeDeps.stream().map(TranspiledCacheEntry::getStrippedBytecodeDir).collect(Collectors.toList()));

            File strippedBytecode = entry.getStrippedBytecodeDir();
            try {
                Javac javac = new Javac(null, strippedClasspath, strippedBytecode, diskCache.getBootstrap());
                List<FrontendUtils.FileInfo> sourcesToCompile = getFileInfoInDir(Paths.get(entry.getStrippedSourcesDir().toURI()), javaMatcher);
                if (sourcesToCompile.isEmpty()) {
                    return entry;
                }
//                System.out.println("step 3 " + project.getArtifactKey());
                boolean javacSuccess = javac.compile(sourcesToCompile);
                if (!javacSuccess) {
                    if (!isIgnoreJavacFailure()) {
                        throw new IllegalStateException("javac failed, check log for details");
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            return entry;
        });
    }

    private CompletableFuture<TranspiledCacheEntry> strippedSources() {
        return getOrCreate(Step.StripGwtIncompatible, () -> {

            // We could probably not make this async, since it runs pretty quickly - but should try it to see if
            // it being parallel buys us anything

            // This depends on running APT on the original sources, and then running the preprocessor tool on both.

            // If there is no instance of the string GwtIncompatible in the code we could skip this entirely?

            return generatedSources().join();
        }, (generatedSources, entry) -> {
            List<FrontendUtils.FileInfo> sourcesToStrip = new ArrayList<>();

            try {
                if (hasSourcesMapped()) {
                    sourcesToStrip.addAll(getFileInfoInDir(entry.getAnnotationSourcesDir().toPath(), javaMatcher));
                    sourcesToStrip.addAll(compileSourceRoots.stream().flatMap(dir -> getFileInfoInDir(Paths.get(dir), javaMatcher).stream()).collect(Collectors.toList()));
                } else {
                    //unpack the jar's sources
                    File sources = entry.getUnpackedSources();

                    //collect sources from jar instead
                    try (ZipFile zipInputFile = new ZipFile(getArtifact().getFile())) {
                        for (ZipEntry z : Collections.list(zipInputFile.entries())) {
                            if (z.isDirectory()) {
                                continue;
                            }
                            Path outPath = sources.toPath().resolve(z.getName());
                            if (javaMatcher.matches(outPath) || nativeJsMatcher.matches(outPath) || jsMatcher.matches(outPath)) {
                                try (InputStream inputStream = zipInputFile.getInputStream(z)) {
                                    Files.createDirectories(outPath.getParent());
                                    Files.copy(inputStream, outPath);
                                    sourcesToStrip.add(FrontendUtils.FileInfo.create(outPath.toString(), z.getName()));
                                }
                            }
                        }
                    }
                }
                GwtIncompatiblePreprocessor stripper = new GwtIncompatiblePreprocessor(entry.getStrippedSourcesDir());
                stripper.preprocess(sourcesToStrip);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }


            return entry;
        });
    }

    private CompletableFuture<TranspiledCacheEntry> generatedSources() {
        return getOrCreate(Step.ProcessAnnotations, () -> {
            return children.stream()
                    .filter(CachedProject::hasSourcesMapped)
                    .filter(child -> new ScopeArtifactFilter(Artifact.SCOPE_COMPILE).include(child.getArtifact()))
                    .map(CachedProject::generatedSources)
//                    .collect(Collectors.toList()).stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());
        }, (reactorBytecode, entry) -> {
            // Using the original sources and non-stripped classpath, run javac to generate a source dir
            // We don't do this at all if drawing on an already-built jar
            if (hasSourcesMapped()) {
                File annotationSources = entry.getAnnotationSourcesDir();
                File plainBytecode = entry.getBytecodeDir();
                List<File> plainClasspath = new ArrayList<>(diskCache.getExtraClasspath());
                plainClasspath.addAll(reactorBytecode.stream().map(TranspiledCacheEntry::getBytecodeDir).collect(Collectors.toList()));
                plainClasspath.addAll(children.stream()
                        .filter(proj -> !proj.hasSourcesMapped())
                        .filter(child -> new ScopeArtifactFilter(Artifact.SCOPE_COMPILE).include(child.getArtifact()))
                        .map(CachedProject::getArtifact)
                        .map(Artifact::getFile)
                        .collect(Collectors.toList())
                );

                List<FrontendUtils.FileInfo> sources = compileSourceRoots.stream().flatMap(dir -> getFileInfoInDir(Paths.get(dir), javaMatcher).stream()).collect(Collectors.toList());
                if (sources.isEmpty()) {
                    return entry;
                }
                try {
                    Javac javac = new Javac(annotationSources, plainClasspath, plainBytecode, diskCache.getBootstrap());
//                    System.out.println("step 1 " + project.getArtifactKey());
                    if (!javac.compile(sources)) {
                        // so far at least we don't have any whitelist need here, it wouldnt really make sense to let a
                        // local compile fail
                        throw new IllegalStateException("javac failed, check log");
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            return entry;
        });
    }

    private CompletableFuture<TranspiledCacheEntry> hash() {
        return getOrCreate(Step.Hash, () -> {
            // wait for hashes of all dependencies
            return children.stream()//TODO filter to scope=compile?
                    .map(CachedProject::hash)
                    .collect(Collectors.toList()).stream()
                    .map(CompletableFuture::join)
                    .map(TranspiledCacheEntry::getHash)
                    .collect(Collectors.toList());
        }, (hashes, ignore) -> {
            Hash hash = new Hash();
            hash.append(diskCache.getPluginVersion().getBytes(Charset.forName("UTF-8")));
            hashes.forEach(h -> hash.append(h.getBytes(Charset.forName("UTF-8"))));
            try {
                if (!compileSourceRoots.isEmpty()) {
                    for (String compileSourceRoot : compileSourceRoots) {
                        appendHashOfAllSources(hash, Paths.get(compileSourceRoot));
                    }
                } else {
                    try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + getArtifact().getFile().toURI()), Collections.emptyMap())) {
                        for (Path rootDirectory : zip.getRootDirectories()) {
                            appendHashOfAllSources(hash, rootDirectory);
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new UncheckedIOException(e);
            }

            return diskCache.entry(getArtifactId(), hash.toString());
        });
    }

    private static void appendHashOfAllSources(Hash hash, Path rootDirectory) throws IOException {
        // TODO filter to only source files - probably will just blacklist .class files?

        // If no sources are found, we still need to consider this as a provided classpath item, but the
        // stripped jar will be empty, etc
        Files.walkFileTree(rootDirectory, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                hash.append(Files.readAllBytes(path));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException e) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private List<FrontendUtils.FileInfo> getFileInfoInDir(Path dir, PathMatcher matcher) {
        try {
            return Files.find(dir, Integer.MAX_VALUE, ((path, basicFileAttributes) -> matcher.matches(path)))
                    .map(p -> FrontendUtils.FileInfo.create(p.toString(), dir.toAbsolutePath().relativize(p).toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


}
