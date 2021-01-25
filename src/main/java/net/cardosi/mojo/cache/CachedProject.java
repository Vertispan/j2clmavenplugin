package net.cardosi.mojo.cache;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.j2cl.transpiler.incremental.ChangeSet;
import com.google.j2cl.transpiler.incremental.TypeGraphStore;
import com.google.j2cl.transpiler.incremental.TypeInfo;
import com.google.javascript.jscomp.*;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.hashing.FileHasher;
import io.methvin.watchservice.MacOSXListeningWatchService;
import io.methvin.watchservice.WatchablePath;
import net.cardosi.mojo.ClosureBuildConfiguration;
import net.cardosi.mojo.Hash;
import net.cardosi.mojo.tools.*;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.model.FileSet;
import org.apache.maven.model.Resource;
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
    public static final String BUNDLE_JAR = "BUNDLE_JAR";
    private static final String BUNDLE_JAR_BASE_FILE = "j2cl-base.js";
    private enum Step {
        Hash,
        ChangeSet,
        ProcessAnnotations,
        StripGwtIncompatible,
        GenerateStrippedBytecode,
        TranspileSources,

        AssembleOutput,

        // these names could be clearer
        JsChecker,
        ProjectBundle,
        ChunkBundle,
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
    private final List<Resource> resources;
    private TypeGraphStore typeGraphStore;

    TranspiledCacheEntry transpiledCacheEntry;

    private final Map<String, CompletableFuture<TranspiledCacheEntry>> steps = new ConcurrentHashMap<>();

    private Set<Supplier<CompletableFuture<TranspiledCacheEntry>>> registeredBuildTerminals = new HashSet<>();

    public CachedProject(DiskCache diskCache, Artifact artifact, MavenProject currentProject, List<CachedProject> children,
                         List<String> compileSourceRoots, List<Resource> resources) {
        this.diskCache = diskCache;
        this.compileSourceRoots = compileSourceRoots;
        this.resources = resources;
        replace(artifact, currentProject, children);
    }

    public CachedProject(DiskCache diskCache, Artifact artifact, MavenProject currentProject, List<CachedProject> children) {
        this(diskCache, artifact, currentProject, children, currentProject.getCompileSourceRoots(), currentProject.getResources());
    }

    public List<String> getCompileSourceRoots() {
        return compileSourceRoots;
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
    public CompletableFuture<Void> markDirty() {
        synchronized (steps) {
            if (steps.isEmpty()) {
                return null;
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
            return build();
        }

        return null;
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

    public CompletableFuture<TranspiledCacheEntry> registerAsApp(ClosureBuildConfiguration config) {
        Supplier<CompletableFuture<TranspiledCacheEntry>> supplier = () -> jscompWithScope(config);
        registeredBuildTerminals.add(supplier);

        // if we're already compiled to this new terminal, then nothing will happen, otherwise we'll build everything
        // that needs building
        return supplier.get();
    }

    private CompletableFuture<Void> build() {
        long start = System.currentTimeMillis();

        return CompletableFuture.allOf(
                registeredBuildTerminals.stream()
                        .map(Supplier::get)
                        .peek(f -> {
                            f.thenAcceptAsync(entry -> {
                                System.out.println(entry.getArtifactId() + " rebuild complete in " + (System.currentTimeMillis() - start) + "ms");
                            });
                        })
                        .toArray(CompletableFuture[]::new)
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
            if (!dir.getCacheDir().exists()) {
                try {
                    Files.createDirectories(dir.getCacheDir().toPath());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            File completeMarker = new File(stepDir, step + "-complete");
            File failedMarker = new File(stepDir, step + "failed");

//            long start = System.currentTimeMillis();
            return CompletableFuture.<TranspiledCacheEntry>supplyAsync(() -> {
                // try to create the step dir. if we succeed, we own it, if we fail, someone already made it, and we wait for them to finish
                while (!stepDir.mkdir()) {
                    // wait for complete/failed markers, if either exists, we can bail early
                    Path stepDirPath = stepDir.toPath();

                    try (WatchService w = createWatchService()) {
                        registerWatch(stepDirPath, w);
                        // first check to see if it exists, then wait for next event to occur
                        do {
                            if (!stepDir.exists()) {
                                //somehow vanished and we didn't get an exception
                                break;
                            }
                            if (completeMarker.exists()) {
                                //                            System.out.println(getArtifactKey() + " " + step +" ready after " + (System.currentTimeMillis() - start) + "ms of waiting");
                                return dir;
                            }
                            if (failedMarker.exists()) {
                                System.out.println("compilation failed in some other thread/process " + getArtifactKey() + " " + dir.getHash());
                                //TODO read out the actual error from the last attempt
                                throw new IllegalStateException("Step " + step + " failed in some other process/thread " + getArtifactKey() + " " + dir.getHash());
                            }

                            // if not, keep waiting and logging until one of them exists
                            // TODO provide a way to timeout and nuke the dir since no one seems to own it
                            System.out.println("Waiting 10s and then checking again if other thread/process finished " + getArtifactKey() + " " + dir.getHash() + " " + step);
                            WatchKey key = w.poll(10, TimeUnit.SECONDS);
                            if (key != null) {
                                key.reset();
                            }

                        } while (true);
                    } catch (NoSuchFileException ex) {
                        // in this case, someone else canceled their work and deleted stepDir itself, causing an exception
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

    public WatchService createWatchService() throws IOException {
        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
        if (isMac) {
            return new MacOSXListeningWatchService(new MacOSXListeningWatchService.Config() {});
        } else {
            return FileSystems.getDefault().newWatchService();
        }
    }

    private void registerWatch(Path stepDirPath, WatchService w) throws IOException {
        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
        Watchable watchable = isMac ? new WatchablePath(stepDirPath) : stepDirPath;
        watchable.register(w, StandardWatchEventKinds.ENTRY_CREATE);
    }

    private <T> CompletableFuture<TranspiledCacheEntry> getOrCreate(String step, Supplier<T> dependencies, BiFunction<T, TranspiledCacheEntry, TranspiledCacheEntry> work) {
        return getOrCreate(step, entry -> CompletableFuture
                        .supplyAsync(() -> {
                    System.out.println("starting dependency step for " + getArtifactKey() + " " + step);
                            try {
                                return dependencies.get();
                            } finally {
                        System.out.println("done with dependency step for " + getArtifactKey() + " " + step);

                            }
                        }, diskCache.queueingPool())
                        .thenApplyAsync(output -> {
                    System.out.println("starting pool work for " + getArtifactKey() + " " + step);
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
        System.out.println("jscompWithScope");
        // first, build the hash of this project and all dependencies
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
            Closure closureCompiler = new Closure();

            PersistentInputStore persistentInputStore = diskCache.getPersistentInputStore();//TODO scope this per app? is that necessary?

            File closureOutputDir = entry.getClosureOutputDir(config);

            CompilationLevel compilationLevel = CompilationLevel.fromString(config.getCompilationLevel());

            //TODO pick another location if sourcemaps aren't going to be used

            File sources;
            String jsOutputDir = new File(closureOutputDir + "/" + config.getInitialScriptFilename()).getParent();
            if (compilationLevel == CompilationLevel.BUNDLE) {
                if (!config.getSourcemapsEnabled()) {
                    //TODO warn that sourcemaps are there anyway, we can't disable in bundle modes?
                }
                sources = new File(jsOutputDir, "sources");
            } else {
                if (config.getSourcemapsEnabled()) {
                    sources = new File(jsOutputDir, "sources");//write to the same place as in bundle mode
                } else {
                    sources = entry.getClosureInputDir();
                }
            }
            reqs.stream().map(TranspiledCacheEntry::getTranspiledSourcesDir).map(File::getAbsoluteFile).distinct().forEach(dir -> {
                try {
                    // don't include the incremental.dat
                    FileUtils.copyDirectory(dir, sources, p -> !p.toString().endsWith("incremental.dat"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }

                //TODO copy to somewhere with a consistent path instead so that we don't change the path each recompile,
                //     then restore the persistent input store
            });

            try {
                Files.createDirectories(Paths.get(closureOutputDir.getAbsolutePath(), config.getInitialScriptFilename()).getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create closure output directory", e);
            }

            Map<String, String> defines = new LinkedHashMap<>(config.getDefines());

            if (compilationLevel == CompilationLevel.BUNDLE) {
                defines.putIfAbsent("goog.ENABLE_DEBUG_LOADER", "false");//TODO maybe overwrite instead?
            }

            boolean success = closureCompiler.compile(
                    compilationLevel,
                    config.getDependencyMode(),
                    sources,
                    diskCache.getExtraJsZips(),
                    config.getEntrypoint(),
                    defines,
                    config.getExterns(),
                    persistentInputStore,
                    true,//TODO have this be passed in,
                    config.getCheckAssertions(),
                    config.getRewritePolyfills(),
                    config.getSourcemapsEnabled(),
                    closureOutputDir + "/" + config.getInitialScriptFilename()
            );

            if (!success) {
                throw new IllegalStateException("Closure Compiler failed, check log for details");
            }

            return entry;
        }).thenApply(entry -> {
            // This will unconditionally run after the lambda which may be cached
            try {
                Path webappPath = Paths.get(config.getWebappDirectory());
                if (!Files.exists(webappPath)) {
                    Files.createDirectories(webappPath);
                }
                FileUtils.copyDirectory(entry.getClosureOutputDir(config), webappPath.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return entry;
        });
    }

    /**
     * An extremely parallelizable but extremely unoptimized way to build an application. This method causes the output
     * JS file to be generated as just a list of other JS files to load in order, one per classpath entry. Those entries
     * can then be built entirely unoptimized and unpruned, so that most of the build output can be cached rather than
     * regenerated as aspects of the application changes.
     */
    public CompletableFuture<TranspiledCacheEntry> registerAsChunkedApp(ClosureBuildConfiguration config) {
        // Produce some one-time results for the whole build, like the JRE guts, pre-bundled.
        // We can achieve this by doing a tiny closure build of just the JRE+bootstrap as one item (which
        // could be separately cached, eventually)
        Closure closureCompiler = new Closure();

        File dir = Paths.get(config.getWebappDirectory(), config.getInitialScriptFilename()).getParent().toFile();

        // be sure we have the directory
        dir.mkdirs();

        Preconditions.checkArgument(config.getDependencyMode() == DependencyOptions.DependencyMode.SORT_ONLY, "With compilationLevel=" + BUNDLE_JAR + " only dependencyMode=SORT_ONLY is supported");

        boolean success = closureCompiler.compile(
                CompilationLevel.BUNDLE,
                DependencyOptions.DependencyMode.SORT_ONLY,
                null,
                diskCache.getExtraJsZips(),
                Collections.emptyList(),
                Collections.emptyMap(),
                config.getExterns(),
                diskCache.getPersistentInputStore(),
                true,//TODO parameterize, but we'll just make it true for now
                config.getCheckAssertions(),
                config.getRewritePolyfills(),
                false,
                dir.getAbsolutePath() + "/" + BUNDLE_JAR_BASE_FILE
        );

        if (!success) {
            // can't begin the regular compile since the base JS won't work, give up
            CompletableFuture<TranspiledCacheEntry> fail = new CompletableFuture<>();
            fail.completeExceptionally(new IllegalStateException("Failed to transpile JRE, can't start up"));
            return fail;
        }

        Supplier<CompletableFuture<TranspiledCacheEntry>> supplier = () -> bundleJarApplication(config);
        registeredBuildTerminals.add(supplier);
        return supplier.get();
    }

    private Stream<CachedProject> flattenDependenciesDepthFirst(CachedProject project, String classpathScope, List<CachedProject> onlyInclude) {
        return Stream.of(
                project.children.stream()
                        .filter(onlyInclude::contains)
                        .filter(child -> new ScopeArtifactFilter(classpathScope).include(child.getArtifact()))
                        .flatMap(child -> flattenDependenciesDepthFirst(child, Artifact.SCOPE_RUNTIME, onlyInclude)),
                Stream.of(project)
        ).flatMap(Function.identity());
    }

    /**
     * Does the actual work of compiling/updating an application using BUNDLE_JAR, producing a bootstrap file, ensuring
     * all dependencies are up to date. The JRE, closure wiring, and defines have already been built by this point,
     * into a file named {@link #BUNDLE_JAR_BASE_FILE}, and this cannot change while the build is running.
     */
    private CompletableFuture<TranspiledCacheEntry> bundleJarApplication(ClosureBuildConfiguration config) {
        // first, build the hash of this project and all dependencies
        System.out.println("bundleJarApplication");
        hash().join();

        File initialScriptFile = Paths.get(config.getWebappDirectory(), config.getInitialScriptFilename()).toFile();

        File outDir = initialScriptFile.getParentFile();


        //incorporate the config into the hash, since we might change config without changing sources
        return getOrCreate(Step.ChunkBundle.name() + "-" + config.hash(), () -> {
            standaloneBundle();//limit our scope
            return children.stream()
                    .filter(child -> {
                        //if child is in any registeredScope item
                        return new ScopeArtifactFilter(config.getClasspathScope()).include(child.getArtifact());
                    })
                    .map(p -> p.standaloneBundle())// for children, we use just the runtime classpath
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());
        }, (bundles, entry) -> {
            // This is the cachable part of the task - nothing to do here, unless we want to copy everything twice.
            // See the unconditionally-running section below.

            return entry;
        }).thenApply(entry -> {
            // This will unconditionally run after the lambda, which may be cached. We will copy any _changed_
            // standaloneBundle output, and then generate the HTML file that serves as the import to it all. In theory
            // we could cache the HTML file, but a) it will be fairly small and cheap to generate, and b) we will
            // have to copy it regardles of cache anyway, in case the webappDirectory was recently cleaned.

            // Collect the dependencies we care about
            // (we assume that j2cl-base.js already exists and that no one is cleaning _while_ we're running)
            Stream<CachedProject> orderedDependencies = flattenDependenciesDepthFirst(this, config.getClasspathScope(), children).distinct();
            List<CachedProject> orderedDependenciesList = orderedDependencies.collect(Collectors.toList());

            // For each dependency that we need at runtime, if its main artifact doesn't exist in the webapp dir,
            // copy it
            //TODO okay for now i'm blindly copying, fix this by testing instead
            try {
                for (CachedProject dep : orderedDependenciesList) {
                    File dir = dep.standaloneBundle().join().getProjBundleDir();//this is already computed, we should never actually block here
                    FileUtils.copyDirectory(dir, outDir);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed in copying output to webapp directory " + config.getWebappDirectory(), e);
            }


            try {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                String scriptsArray = gson.toJson(Stream.concat(
                        Stream.of(BUNDLE_JAR_BASE_FILE),//jre and wiring and defines, already in the dir
                        flattenDependenciesDepthFirst(this, config.getClasspathScope(), children)
                                .distinct()
                                .map(p -> p.getArtifactId() + "-" + p.hash().join().getHash() + ".bundle.js")// each of our bundles, copied above
                ).collect(Collectors.toList()));
                Map<String, Object> defines = new LinkedHashMap<>(config.getDefines());
                // unconditionally set this to false, so that our dependency order works, since we're always in BUNDLE now
                defines.put("goog.ENABLE_DEBUG_LOADER", false);

                // defines are global, outside the IIFE
                String defineLine = "var CLOSURE_UNCOMPILED_DEFINES = " + gson.toJson(defines) + ";\n";
                // IIFE and base url
                String intro = "(function() {" + "var src = document.currentScript.src;\n" +
                        "var lastSlash = src.lastIndexOf('/');\n" +
                        "var base = lastSlash === -1 ? '' : src.substr(0, lastSlash + 1);";

                // iterate the scripts and append, close IIFE
                String outro = ".forEach(file => {\n" +
                        "  var elt = document.createElement('script');\n" +
                        "  elt.src = base + file;\n" +
                        "  elt.type = 'text/javascript';\n" +
                        "  elt.async = false;\n" +
                        "  document.head.appendChild(elt);\n" +
                        "});" + "})();";
                Files.write(initialScriptFile.toPath(), Arrays.asList(
                        defineLine,
                        intro,
                        scriptsArray,
                        outro
                ));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write html import file", e);
            }

            return entry;
        });
    }

    /**
     * Emits a single compilationLevel=BUNDLE for this project only, without any dependencies.
     */
    private CompletableFuture<TranspiledCacheEntry> standaloneBundle() {
        return getOrCreate(Step.ProjectBundle.name(), () -> {
            // for now just generating this project's own js - in theory should generate externs too via jsChecker()
            return j2cl().join();
        }, (ownSources, entry) -> {
            Closure closureCompiler = new Closure();

            File closureOutputDir = entry.getProjBundleDir();

            try {
                Files.createDirectories(closureOutputDir.toPath());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create closure output directory", e);
            }

            String outputFile = closureOutputDir + "/" + getArtifactId() + "-" + entry.getHash() + ".bundle.js";

            //if no js sources exist, write an empty file and exit
            //TODO alternative to this lazy check of contents, enumerate the js files, and pass them directly to closure
            try(DirectoryStream<Path> dirStream = Files.newDirectoryStream(ownSources.getTranspiledSourcesDir().toPath())) {
                if (!dirStream.iterator().hasNext()) {
                    Files.createFile(Paths.get(outputFile));
                    return entry;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to enumerate files or write empty file", e);
            }

            // copy the sources locally so that we can build real sourcemaps
            File sources = new File(closureOutputDir, "sources");

            try {
                FileUtils.copyDirectory(ownSources.getTranspiledSourcesDir(), sources);
            } catch (IOException e) {
                e.printStackTrace();
                throw new UncheckedIOException(e);
            }

            boolean success = closureCompiler.compile(
                    CompilationLevel.BUNDLE,
                    DependencyOptions.DependencyMode.SORT_ONLY,
                    sources,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyList(),//TODO actually pass these in when we can restrict and cache them sanely
                    diskCache.getPersistentInputStore(),
                    true,//TODO have this be passed in,
                    true,//default to true, will have no effect anyway
                    false,
                    false,
                    outputFile
            );

            if (!success) {
                throw new IllegalStateException("Closure Compiler failed, check log for details");
            }

            return entry;
        });
    }

    public CompletableFuture<TranspiledCacheEntry> jsCheck(String classpathScope) {
        // Given the classpath scope, select all depenedencies that apply and j2cl them, then j2Checker() them,
        // and return the results from this specific project. This seems like it could have been recursive, but
        // that approach gets screwed up around optional and provided dependencies.

        // easy hack for now
        return jsChecker();
    }

    /**
     * Checks this project in closure (roughly the equivalent of building _just_ this
     * project with a full ADVANCED compile) against the externs of its dependencies,
     * and emits a externs file which can then be used by other projects for this same
     * step.
     */
    private CompletableFuture<TranspiledCacheEntry> jsChecker() {
        return getOrCreate(Step.JsChecker.name(), () -> {
            // transpile our own JS - we assume this is complete, so it finishes trivially
//            j2cl().join();

//            System.out.println(getArtifactKey() + "'s children ("+classpathScope+"): " + children.stream().filter(child -> {
//                return new ScopeArtifactFilter(classpathScope).include(child.getArtifact());
//            }).map(CachedProject::getArtifact).map(Object::toString).collect(Collectors.joining(", ")));
            // build all the externs for our dependencies (which will in turn transpile them, etc)
            return children.stream()// I'm not even sure we need upstream deps at this point
                    .filter(child -> {
                        return new ScopeArtifactFilter(Artifact.SCOPE_RUNTIME).include(child.getArtifact());
                    })
                    .map(p -> {
//                        System.out.println(getArtifactKey() + " jsChecker -> " + p.getArtifactKey() + " jsChecker(RUNTIME)");
//                        System.out.println(p.getArtifact().getDependencyTrail());
                        return p.jsChecker();
                    })// for children, we use just the runtime classpath
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());
        }, (externDeps, entry) -> {
            List<File> upstreamExterns = externDeps.stream().map(TranspiledCacheEntry::getExternsFile).collect(Collectors.toList());
            JsChecker checker = new JsChecker(upstreamExterns);

            // is not incremental, so passes all files.
            boolean success = checker.checkAndGenerateExterns(getFileInfoInDir(entry.getTranspiledSourcesDir().toPath(), false, jsMatcher), entry.getExternsFile());

            return entry;
        });
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
//            if (reactorProject) {
//                System.out.println("changeset: " + typeGraphStore.getChangeSet().getSourcesToProcesSet() );
//            }
            List<Path> sourcesToCompile = getFileInfoInDir(Paths.get(entry.getStrippedSourcesDir().toURI()),
                                                           true, javaMatcher);
            if (!sourcesToCompile.isEmpty()) {
                //invoke j2cl on these sources, classpath
                List<File> bytecodeClasspath = new ArrayList<>();
                // add itself, this is needed for incremental.
                // As .java takes classpath priority over .class, the shadowing should not be an issue.
                bytecodeClasspath.add(entry.getBytecodeDir());
                bytecodeClasspath.addAll(bytecodeDeps.stream().map(TranspiledCacheEntry::getStrippedBytecodeDir).collect(Collectors.toList()));
                bytecodeClasspath.addAll(diskCache.getExtraClasspath());

                //List<File> bytecodeClasspath = new ArrayList<>(bytecodeDeps.stream().map(TranspiledCacheEntry::getStrippedBytecodeDir).collect(Collectors.toList()));



                J2cl j2cl = new J2cl(bytecodeClasspath, diskCache.getBootstrap(), entry.getTranspiledSourcesDir(), hasSourcesMapped());
                List<Path> nativeSources = getFileInfoInDir(entry.getStrippedSourcesDir().toPath(), true, nativeJsMatcher);
                maybeAddNativeJs(sourcesToCompile, nativeSources);
                boolean j2clSuccess = j2cl.transpile(sourcesToCompile, nativeSources);
                if (!j2clSuccess) {
                    throw new IllegalStateException("j2cl failed, check log for details");
                }
            }

            //copy over other plain js
            Path outSources = entry.getTranspiledSourcesDir().toPath();
            getFileInfoInDir(entry.getStrippedSourcesDir().toPath(), true, path -> jsMatcher.matches(path) && !nativeJsMatcher.matches(path))
                    .stream()
                    .map(p -> entry.getStrippedSourcesDir().toPath().relativize(p))
                    .forEach(path -> {
                        try {
                            Files.createDirectories(outSources.resolve(path).getParent());
                            Files.copy(entry.getStrippedSourcesDir().toPath().resolve(path), outSources.resolve(path), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });

            return entry;
        });
    }

    private void maybeAddNativeJs(List<Path> sourcesToCompile, List<Path> nativeSources) {
        for (Path path : sourcesToCompile) {
            File maybeNativeJs = new File(path.toString().replaceAll("\\.java","\\.native.js"));
            if(maybeNativeJs.exists() && !nativeSources.contains(maybeNativeJs.toPath())) {
                nativeSources.add(maybeNativeJs.toPath());
            }
        }
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

            // add itself, this is needed for incremental.
            // As .java takes classpath priority over .class, the shadowing should not be an issue.
            strippedClasspath.add(entry.getStrippedBytecodeDir());

            File strippedBytecode = entry.getStrippedBytecodeDir();
            try {
                // Recompile just the classes from the ChangeSet
                Javac javac = new Javac(null, strippedClasspath, strippedBytecode, diskCache.getBootstrap());
                List<Path> sourcesToCompile = getFileInfoInDir(Paths.get(entry.getStrippedSourcesDir().toURI()),
                                                               true,
                                                               javaMatcher);
                if (sourcesToCompile.isEmpty()) {
                    return entry;
                }
//                System.out.println("step 3 " + project.getArtifactKey());
                boolean javacSuccess = javac.compile(sourcesToCompile);
                if (!javacSuccess) {
                    throw new IllegalStateException("javac failed, check log for details");
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
            GwtIncompatiblePreprocessor stripper = new GwtIncompatiblePreprocessor(entry.getStrippedSourcesDir());
            try {
                List<String> dirsToStrip = new ArrayList<>();

                if (hasSourcesMapped()) {
                    dirsToStrip.addAll(compileSourceRoots);
                    dirsToStrip.add(entry.getAnnotationSourcesDir().toString());
                } else {
//unpack the jar's sources
                    File sources = entry.getUnpackedSources();
                    dirsToStrip.add(sources.toString());
                    dirsToStrip.add(sources.toString());

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
                                }
                            }
                        }
                    }
                }

                // For each added source jar, find the files and strip and output to stripped-sources
                dirsToStrip.stream().forEach(dir -> {
                    Path dirPath = Paths.get(dir);
                    List<Path> sourcesToStrip = getFileInfoInDir(dirPath, true, javaMatcher, nativeJsMatcher, jsMatcher);
                    try {
                        stripper.preprocess(dirPath.toFile(), sourcesToStrip);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return entry;
        });
    }

    // this is public since we need to generate sources to see what tests we run
    public CompletableFuture<TranspiledCacheEntry> generatedSources() {
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
                buildChangeSets(reactorBytecode, entry);

                File annotationSources = entry.getAnnotationSourcesDir();
                File plainBytecode = entry.getBytecodeDir();//output dir for bytecode while generating sources
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
                plainClasspath.addAll(resources.stream().map(FileSet::getDirectory).map(File::new).collect(Collectors.toList()));
//                plainClasspath.addAll(compileSourceRoots.stream().map(File::new).collect(Collectors.toList()));

                // This currently processes the annnotations for the entire project and compiles them (it is not incremental)
                List<Path> sources = compileSourceRoots.stream().flatMap(dir -> getFileInfoInDir(Paths.get(dir), false, javaMatcher).stream()).collect(Collectors.toList());

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

                System.out.println("hash " + artifact.getArtifactId() + " : " + artifact.getVersion() + " : " + hash.toString() );
                return diskCache.entry(getArtifactId(), hash.toString());
            } catch (IOException e) {
                e.printStackTrace();
                throw new UncheckedIOException(e);
            }

        });
    }

    static void copyFolder(File src, File dest){
        try {
            FileUtils.copyDirectory(src, dest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return;
    }

    private void buildChangeSets(List<TranspiledCacheEntry> reactorBytecode, TranspiledCacheEntry entry) {
        if(transpiledCacheEntry != null) {
            TranspiledCacheEntry srcEntry = transpiledCacheEntry;
            Path srcBase = entry.getCacheDir().toPath();
            Path trgBase = entry.getCacheDir().toPath();
//                    copyFolder(srcBase.resolve(srcEntry.getAnnotationSourcesDir().toPath()).toFile(),
//                               trgBase.resolve(entry.getAnnotationSourcesDir().toPath()).toFile());

            copyFolder(srcBase.resolve(srcEntry.getBytecodeDir().toPath()).toFile(),
                       trgBase.resolve(entry.getBytecodeDir().toPath()).toFile());

            copyFolder(srcBase.resolve(srcEntry.getTranspiledSourcesDir().toPath()).toFile(),
                       trgBase.resolve(entry.getTranspiledSourcesDir().toPath()).toFile());

            copyFolder(srcBase.resolve(srcEntry.getStrippedBytecodeDir().toPath()).toFile(),
                       trgBase.resolve(entry.getStrippedBytecodeDir().toPath()).toFile());

            copyFolder(srcBase.resolve(srcEntry.getStrippedSourcesDir().toPath()).toFile(),
                       trgBase.resolve(entry.getStrippedSourcesDir().toPath()).toFile());

            copyFolder(srcBase.resolve(srcEntry.getUnpackedSources().toPath()).toFile(),
                       trgBase.resolve(entry.getUnpackedSources().toPath()).toFile());
        }
        transpiledCacheEntry = entry;

        try {
            typeGraphStore = new TypeGraphStore();

            reactorBytecode.stream().forEach(e -> typeGraphStore.addAllToDelegate(e.getImpacting(), e.getUniqueIdToPath() ));
            typeGraphStore.calculateChangeSet(entry.getTranspiledSourcesDir().toPath(), compileSourceRoots);
            typeGraphStore.write();
            // TODO generated sources here. They will always appear new each time, but that's ok, it helps keep the rest of the code simpler.


            // TODO don't hard code this to 1 ChangeSet (mdp)
            ChangeSet[] changeSets = typeGraphStore.getChangeSets().values().toArray(new ChangeSet[typeGraphStore.getChangeSets().size()]);
            ChangeSet changeSet = changeSets[0];
            for (String updated : changeSet.getUpdated()) {
                if ( updated.endsWith(".native.js")) {
                    deleteNativeJsSource(entry, updated);
                } else if ( updated.endsWith(".java")) {
                    deleteJavaSource(entry, updated);
                    String uniqueId = typeGraphStore.getPathToUniqueId().get(updated);
                    deleteInnerTypes(entry, updated, typeGraphStore.getInnerTypesChanged().get(uniqueId));

                    // TODO delete nested classes
                } else {
                    System.out.println("Did not Delete: " + updated);
                }
            }

            for (String removed : changeSet.getRemoved()) {
                if ( removed.endsWith(".native.js")) {
                    deleteNativeJsSource(entry, removed);
                } else if ( removed.endsWith(".java")) {
                    deleteJavaSource(entry, removed);
                    String uniqueId = typeGraphStore.getPathToUniqueId().get(removed);
                    deleteInnerTypes(entry, removed, typeGraphStore.getInnerTypesChanged().get(uniqueId));
                } else {
                    System.out.println("Did not Delete: " + removed);
                }
            }

            entry.getImpacting().clear();
            List<TypeInfo> clonedImpacting = typeGraphStore.getImpactingTypeInfos();
            entry.getImpacting().addAll(clonedImpacting);

            entry.getUniqueIdToPath().clear();
            entry.getUniqueIdToPath().putAll(typeGraphStore.getUniqueIdToPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void deleteInnerTypes(TranspiledCacheEntry entry, String updated, List<String> innerTypes) throws IOException {
        if (innerTypes != null && !innerTypes.isEmpty()) {
            System.out.println("deleteInner: " + updated + ":" + innerTypes);
            String baseFileName = updated.substring(0, updated.length() - 5);
            for (String innerType : innerTypes) {
                String innerFileName = baseFileName + innerType.substring(innerType.indexOf('$')) + ".java";
                deleteJavaSource(entry, innerFileName);
            }
        }
    }

    private void deleteNativeJsSource(TranspiledCacheEntry entry, String updated) throws IOException {
        Path strippedJsPath = entry.getStrippedSourcesDir().toPath().resolve(updated);
        Files.deleteIfExists(strippedJsPath);

        Path strippedJsPath_js = entry.getStrippedSourcesDir().toPath()
                                      .resolve(updated.substring(0, updated.length() - 9) + "native_js");
        Files.deleteIfExists(strippedJsPath_js);
    }

    private void deleteJavaSource(TranspiledCacheEntry entry, String file) throws IOException {
        Path javaPath = entry.getStrippedSourcesDir().toPath().resolve(file);
        boolean b1 = Files.deleteIfExists(javaPath); // this won't exist for inner types, but that's ok

        String baseFileName = file.substring(0, file.length() - 5);
        Path   bytecodPath  = entry.getBytecodeDir().toPath().resolve(baseFileName + ".class");
        boolean b2 = Files.deleteIfExists(bytecodPath);

        Path javaJsPath = entry.getTranspiledSourcesDir().toPath().resolve(baseFileName + ".java.js");
        boolean b3 = Files.deleteIfExists(javaJsPath);

        Path implJavaJsPath = entry.getTranspiledSourcesDir().toPath().resolve(baseFileName + ".impl.java.js");
        boolean b4 = Files.deleteIfExists(implJavaJsPath);

        Path jsMap = entry.getTranspiledSourcesDir().toPath().resolve(baseFileName + ".js.map");
        boolean b5 = Files.deleteIfExists(jsMap);

        System.out.println("Delete: " + b1 + ":" + javaPath);
        System.out.println("Delete: " + b2 + ":" + bytecodPath);
        System.out.println("Delete: " + b3 + ":" + javaJsPath);
        System.out.println("Delete: " + b4 + ":" + implJavaJsPath);
        System.out.println("Delete: " + b5 + ":" + jsMap);
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

    private List<Path> getFileInfoInDir(Path dir, boolean useTypeStoreMatcher, PathMatcher... matcher) {
        if (!Files.exists(dir)) {
            return Collections.emptyList();
        }
        try {
            final PathMatcher typeStoreMatcher;
            if (useTypeStoreMatcher && typeGraphStore != null) {
//                System.out.println("dir: " + dir);
//                System.out.println("dirs: " + typeGraphStore.getChangeSets().keySet());
                //System.out.println("sources: " + typeGraphStore.getChangeSets().get(dir).getSourcesToProcesSet());

                typeStoreMatcher = p -> {
                    p = dir.relativize(p);
                    boolean found = false;
                    String str = p.toString();
                    for (ChangeSet changeSet : typeGraphStore.getChangeSets().values()) {
                        if (changeSet.getSourcesToProcesSet().contains(str)) {
                            found = true;
                            break;
                        }
                    }
//                    System.out.println("match: " + p.toString() + " " + found);
                    return found;
                };
            } else {
                typeStoreMatcher = null;
            }

            return Files.find(dir, Integer.MAX_VALUE, ((path, basicFileAttributes) -> Arrays.stream(matcher).anyMatch(m -> m.matches(path)
                                                                                                                           && (typeStoreMatcher == null || typeStoreMatcher.matches(path)))))
                        //.map(p -> FrontendUtils.FileInfo.create(p.toString(), dir.toAbsolutePath().relativize(p).toString()))
                        .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        CachedProject that = (CachedProject) o;

        return artifact.equals(that.artifact);
    }

    @Override public int hashCode() {
        return artifact.hashCode();
    }


}
