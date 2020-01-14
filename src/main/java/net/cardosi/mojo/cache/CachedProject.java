package net.cardosi.mojo.cache;

import com.google.j2cl.common.FrontendUtils;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.*;
import net.cardosi.mojo.ClosureBuildConfiguration;
import net.cardosi.mojo.Hash;
import net.cardosi.mojo.tools.GwtIncompatiblePreprocessor;
import net.cardosi.mojo.tools.J2cl;
import net.cardosi.mojo.tools.Javac;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.model.FileSet;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
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

    private final Map<String, CompletableFuture<TranspiledCacheEntry>> steps = new ConcurrentHashMap<>();

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
            // cancel all running work
            for (CompletableFuture<TranspiledCacheEntry> cf : steps.values()) {
                try {
                    cf.cancel(true);
                } catch (Exception e) {
                    throw new RuntimeException("Failed while canceling?", e);
                }
            }
            // wait for the running work to actually terminate
            for (CompletableFuture<TranspiledCacheEntry> cf : steps.values()) {
                try {
                    cf.join();
                } catch (Exception ignored) {
                    // ignore errors, we're expecting this
                }
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

    public void watch() throws IOException {
        Map<FileSystem, List<Path>> fileSystemsToWatch = compileSourceRoots.stream().map(Paths::get).collect(Collectors.groupingBy(Path::getFileSystem));
        for (Map.Entry<FileSystem, List<Path>> entry : fileSystemsToWatch.entrySet()) {
            WatchService watchService = entry.getKey().newWatchService();
            for (Path path : entry.getValue()) {
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                        path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                        return FileVisitResult.CONTINUE;
                    }
                });
                path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
            }
            new Thread() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            WatchKey key = watchService.poll(10, TimeUnit.SECONDS);
                            if (key == null) {
                                continue;
                            }
                            //TODO if it was a create, register it (recursively?)
                            key.pollEvents();//clear the events out
                            key.reset();//reset to go again
                            markDirty();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }.start();
        }
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

    private CompletableFuture<TranspiledCacheEntry> getOrCreate(String step, Function<TranspiledCacheEntry, CompletableFuture<TranspiledCacheEntry>> instructions) {
//        synchronized (steps) {
//        System.out.println("requested " + getArtifactKey() + " " + step);
        return steps.computeIfAbsent(step, ignore -> {
            if (step.equals(Step.Hash.name())) {
                return instructions.apply(null);
            }
            // first check if it is already on disk (doesn't apply to Hash)
            TranspiledCacheEntry dir = hash().join();
            // try to create the dir - if we succeed, we own the lock, continue
            File stepDir = new File(dir.getCacheDir(), step);

            File completeMarker = new File(stepDir, step + "-complete");
            File failedMarker = new File(stepDir, step + "failed");

//            long start = System.currentTimeMillis();
            return CompletableFuture.<TranspiledCacheEntry>supplyAsync(() -> {

                while (!stepDir.mkdirs()) {
                    // wait for complete/failed markers, if either exists, we can bail early
                    Path cacheDirPath = Paths.get(dir.getCacheDir().toURI());
                    try (WatchService w = cacheDirPath.getFileSystem().newWatchService()) {
                        cacheDirPath.register(w, StandardWatchEventKinds.ENTRY_CREATE);
                        // first check to see if it exists, then wait for next event to occur
                        do {
                            if (completeMarker.exists()) {
                                //                            System.out.println(getArtifactKey() + " " + step +" ready after " + (System.currentTimeMillis() - start) + "ms of waiting");
                                return dir;
                            }
                            if (failedMarker.exists()) {
                                System.out.println("compilation failed in some other thread/process " + getArtifactKey() + " " + dir.getHash());
                                //TODO read out the actual error from the last attempt
                                throw new IllegalStateException("Step " + step + " failed in some other process/thread " + getArtifactKey() + " " + dir.getHash());
                            }

                            // if not, keep waiting and logging
                            // TODO provide a way to timeout and nuke the dir since no one seems to own it
                            System.out.println("Waiting 10s and then checking again if other thread/process finished " + getArtifactKey());
                            w.poll(10, TimeUnit.SECONDS);
                        } while (true);
                    } catch (NoSuchFileException ex) {
                        //noinspection UnnecessaryContinue
                        continue;//try to make the directory again - explicit continue for readability
                    } catch (IOException | InterruptedException ex) {
                        //TODO expect a funky exception here if the stepDir itself gets deleted since a process was canceled? shouldn't really matter, we'll be interrupted anyway in here
                        System.out.println("Error while waiting for external thread/process");
                        ex.printStackTrace();
                        throw new RuntimeException(ex);
                    }
                }
                return instructions.apply(dir).whenComplete((success, failure) -> {
                    try {

                        if (failure == null) {
//                        System.out.println("completed " + getArtifactKey() + " " + step + " in " + (System.currentTimeMillis() - start) + "ms of waiting");
                            Files.createFile(completeMarker.toPath());
                        } else if (failure instanceof CancellationException || (failure instanceof CompletionException && failure.getCause() instanceof CancellationException)) {
                            // failure since we changed our minds and don't want this now, before it managed to complete, mop up
                            assert stepDir.listFiles().length == 0;
                            stepDir.delete();
                        } else {
                            System.out.println("failed " + getArtifactKey() + " " + step + " " + failure.getClass() + ":" + failure.getMessage());
                            failure.printStackTrace();
                            Files.createFile(failedMarker.toPath());
                        }
                    } catch (IOException ex) {
                        ex.printStackTrace();
                        throw new UncheckedIOException(ex);
                    }
                }).join();
            }, diskCache.queueingPool());
        });
    }

    private <T> CompletableFuture<TranspiledCacheEntry> getOrCreate(String step, Supplier<T> dependencies, BiFunction<T, TranspiledCacheEntry, TranspiledCacheEntry> work) {
        return getOrCreate(step, entry -> CompletableFuture
                        .supplyAsync(() -> {
//                    System.out.println("starting dependency step for " + getArtifactKey() + " " + step);
                            try {
                                return dependencies.get();
                            } finally {
//                        System.out.println("done with dependency step for " + getArtifactKey() + " " + step);

                            }
                        }, diskCache.queueingPool())
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
        // first, build the has of this and all dependencies
        hash().join();

        return getOrCreate(Step.AssembleOutput.name() + "-" + config.hash(), () -> {


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

            File closureOutputDir = entry.getClosureOutputDir(config);

            Compiler jsCompiler = new Compiler(System.err);
//            jsCompiler.setPersistentInputStore(persistentInputStore);

            CompilationLevel compilationLevel = CompilationLevel.fromString(config.getCompilationLevel());

            List<String> jscompArgs = new ArrayList<>();
            //TODO pick another location if sourcemaps aren't going to be used

            File sources;
            String jsOutputDir = new File(closureOutputDir + "/" + config.getInitialScriptFilename()).getParent();
            if (compilationLevel == CompilationLevel.BUNDLE) {
                sources = new File(jsOutputDir, "sources");
            } else {
                sources = entry.getClosureOutputDir(config);
            }
            reqs.stream().map(TranspiledCacheEntry::getTranspiledSourcesDir).map(File::getAbsolutePath).distinct().forEach(dir -> {
                try {
                    FileUtils.copyDirectory(new File(dir), sources);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                //add this dir as to be compiled
//                jscompArgs.add("--js");
//                jscompArgs.add(dir + "/**/*.js");

                //TODO copy to somewhere with a consistent path instead so that we don't change the path each recompile,
                //     then restore the persistent input store
            });
            // add all to be compiled
            jscompArgs.add("--js");
            jscompArgs.add(sources + "/**/*.js");

            //TODO scope=runtime jszips
            //TODO unpack these so we can offer sourcemaps
            diskCache.getExtraJsZips().forEach(file -> {
                jscompArgs.add("--jszip");
                jscompArgs.add(file.getAbsolutePath());
            });

            if (compilationLevel == CompilationLevel.BUNDLE) {
                jscompArgs.add("--define");
                jscompArgs.add("goog.ENABLE_DEBUG_LOADER=false");
            }

            for (Map.Entry<String, String> define : config.getDefines().entrySet()) {
                jscompArgs.add("--define");
                jscompArgs.add(define.getKey() + "=" + define.getValue());
            }

            for (String extern : config.getExterns()) {
                jscompArgs.add("--externs");
                jscompArgs.add(extern);
            }

            jscompArgs.add("--compilation_level");
            jscompArgs.add(compilationLevel.name());

            jscompArgs.add("--dependency_mode");
            jscompArgs.add(DependencyOptions.DependencyMode.PRUNE.name());


            jscompArgs.add("--language_out");
            jscompArgs.add("ECMASCRIPT5");

            for (String entrypoint : config.getEntrypoint()) {
                jscompArgs.add("--entry_point");
                jscompArgs.add(entrypoint);
            }

            try {
                Files.createDirectories(Paths.get(closureOutputDir.getAbsolutePath(), config.getInitialScriptFilename()).getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create closure output directory", e);
            }

            jscompArgs.add("--js_output_file");
            jscompArgs.add(closureOutputDir + "/" + config.getInitialScriptFilename());


            //TODO bundles

            InProcessJsCompRunner jscompRunner = new InProcessJsCompRunner(jscompArgs.toArray(new String[0]), jsCompiler);
            if (!jscompRunner.shouldRunCompiler()) {
                jscompArgs.forEach(System.out::println);
                throw new IllegalStateException("Closure Compiler setup error, check log for details");
            }

            //TODO historically we didnt populate the persistent input store until this point, so put it here
            //     if we restore it

            try {
                jscompRunner.run();

                if (jscompRunner.hasErrors() || jscompRunner.exitCode != 0) {
                    throw new IllegalStateException("closure compiler failed, check log for details");
                }
            } finally {
                if (jsCompiler.getModules() != null) {
                    // clear out the compiler input for the next go-around
                    jsCompiler.resetCompilerInput();
                }
            }


            return entry;
        }).thenApply(entry -> {
            //This will unconditionally run after the lambda which may be cached
            new File(config.getWebappDirectory()).mkdirs();
            try {
                FileUtils.copyDirectory(entry.getClosureOutputDir(config), new File(config.getWebappDirectory()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return entry;
        });
    }
    static class InProcessJsCompRunner extends CommandLineRunner {

        private final Compiler compiler;
        private Integer exitCode;

        InProcessJsCompRunner(String[] args, Compiler compiler) {
            super(args);
            this.compiler = compiler;
            setExitCodeReceiver(exitCode -> {
                this.exitCode = exitCode;
                return null;
            });
        }

        @Override
        protected Compiler createCompiler() {
            return compiler;
        }
    }


    private CompletableFuture<TranspiledCacheEntry> j2cl() {
        return getOrCreate(Step.TranspileSources.name(), () -> {

            // collect all scope=compile dependencies and our own stripped sources

            strippedSources().join();
            return children.stream()
//                    .filter(child -> {
//                        return new ScopeArtifactFilter(Artifact.SCOPE_COMPILE).include(child.getArtifact());//TODO removing this is wrong, should instead let the whole "project" be scoped
//                    })
                    .map(child -> child.strippedBytecode())
//                    .collect(Collectors.toList()).stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());


        }, (bytecodeDeps, entry) -> {
            List<FrontendUtils.FileInfo> sourcesToCompile = getFileInfoInDir(Paths.get(entry.getStrippedSourcesDir().toURI()), javaMatcher);
            if (!sourcesToCompile.isEmpty()) {
                //invoke j2cl on these sources, classpath
                List<File> strippedClasspath = new ArrayList<>(bytecodeDeps.stream().map(TranspiledCacheEntry::getStrippedBytecodeDir).collect(Collectors.toList()));
                strippedClasspath.addAll(diskCache.getExtraClasspath());

                J2cl j2cl = new J2cl(strippedClasspath, diskCache.getBootstrap(), entry.getTranspiledSourcesDir());
                List<FrontendUtils.FileInfo> nativeSources = getFileInfoInDir(entry.getStrippedSourcesDir().toPath(), nativeJsMatcher);

                boolean j2clSuccess = j2cl.transpile(sourcesToCompile, nativeSources);
                if (!j2clSuccess) {
                    if (!isIgnoreJavacFailure()) {
                        throw new IllegalStateException("j2cl failed, check log for details");
                    }
                }
            }

            //copy over other plain js
            Path outSources = entry.getTranspiledSourcesDir().toPath();
            getFileInfoInDir(entry.getStrippedSourcesDir().toPath(), path -> jsMatcher.matches(path) && !nativeJsMatcher.matches(path))
                    .stream()
                    .map(FrontendUtils.FileInfo::sourcePath).map(Paths::get)
                    .map(p -> entry.getStrippedSourcesDir().toPath().relativize(p))
                    .forEach(path -> {
                        try {
                            Files.createDirectories(outSources.resolve(path).getParent());
                            Files.copy(entry.getStrippedSourcesDir().toPath().resolve(path), outSources.resolve(path));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });

            return entry;
        });
    }

    private CompletableFuture<TranspiledCacheEntry> strippedBytecode() {
        return getOrCreate(Step.GenerateStrippedBytecode.name(), () -> {

            // If there is no sources, or no sources with GwtIncompatible, then just return the original bytecode as-is.
            // This lets us get away with not being able to transpile annotation processors or their dependencies, or
            // artifacts which just include compile-time stuff like annotations.
            //TODO

            // In order to generate bytecode, we strip the sources, and javac that against the stripped bytecode of all
            // scope=compile dependencies

            strippedSources().join();
            return children.stream()
//                    .filter(child -> {
//                        return new ScopeArtifactFilter(Artifact.SCOPE_COMPILE).include(child.getArtifact());//TODO removing this is wrong, should instead let the whole "project" be scoped
//                    })
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
        return getOrCreate(Step.StripGwtIncompatible.name(), () -> {

            // We could probably not make this async, since it runs pretty quickly - but should try it to see if
            // it being parallel buys us anything

            // This depends on running APT on the original sources, and then running the preprocessor tool on both.

            // If there is no instance of the string GwtIncompatible in the code we could skip this entirely?

            return generatedSources().join();
        }, (generatedSources, entry) -> {
            List<FrontendUtils.FileInfo> sourcesToStrip = new ArrayList<>();

            try {
                if (hasSourcesMapped()) {
                    sourcesToStrip.addAll(getFileInfoInDir(entry.getAnnotationSourcesDir().toPath(), javaMatcher, nativeJsMatcher, jsMatcher));
                    sourcesToStrip.addAll(compileSourceRoots.stream().flatMap(dir -> getFileInfoInDir(Paths.get(dir), javaMatcher, nativeJsMatcher, jsMatcher).stream()).collect(Collectors.toList()));
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
        return getOrCreate(Step.ProcessAnnotations.name(), () -> {
            // depend on other projects with sources mapped, and only ask for "generated sources" so that we can get their unstripped bytecode
            return children.stream()
                    .filter(CachedProject::hasSourcesMapped)
//                    .filter(child -> new ScopeArtifactFilter(Artifact.SCOPE_COMPILE).include(child.getArtifact()))//TODO removing this is wrong, should instead let the whole "project" be scoped
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
//                        .filter(child -> new ScopeArtifactFilter(Artifact.SCOPE_COMPILE).include(child.getArtifact()))//TODO removing this is wrong, should instead let the whole "project" be scoped
                                .map(CachedProject::getArtifact)
                                .map(Artifact::getFile)
                                .collect(Collectors.toList())
                );

                //also add the source dir as if it were on the classpath, as resources
                plainClasspath.addAll(currentProject.getResources().stream().map(FileSet::getDirectory).map(File::new).collect(Collectors.toList()));
//                plainClasspath.addAll(compileSourceRoots.stream().map(File::new).collect(Collectors.toList()));

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
        return getOrCreate(Step.Hash.name(), () -> {
            // wait for hashes of all dependencies
            return children.stream()//TODO filter to scope=compile?
                    .map(CachedProject::hash)
                    .collect(Collectors.toList()).stream()
                    .map(CompletableFuture::join)
                    .map(TranspiledCacheEntry::getHash)
                    .collect(Collectors.toList());
        }, (hashes, ignore) -> {
            Hash hash = new Hash();
            hash.append(diskCache.getPluginVersion());
            hashes.forEach(hash::append);
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

    private List<FrontendUtils.FileInfo> getFileInfoInDir(Path dir, PathMatcher... matcher) {
        if (!Files.exists(dir)) {
            return Collections.emptyList();
        }
        try {
            return Files.find(dir, Integer.MAX_VALUE, ((path, basicFileAttributes) -> Arrays.stream(matcher).anyMatch(m -> m.matches(path))))
                    .map(p -> FrontendUtils.FileInfo.create(p.toString(), dir.toAbsolutePath().relativize(p).toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


}
