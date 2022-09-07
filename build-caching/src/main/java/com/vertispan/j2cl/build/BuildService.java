package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;
import com.vertispan.j2cl.build.incremental.BuildMap;
import com.vertispan.j2cl.build.incremental.BuildMapHashReader;
import com.vertispan.j2cl.build.incremental.JavaFileHashReader;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.TaskFactory;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuildService {
    private final TaskRegistry taskRegistry;
    private final TaskScheduler taskScheduler;
    private final DiskCache diskCache;
    private final Map<Project, BuildMap> buildMaps = new ConcurrentHashMap<>();

    // all registered project+task items that might need to be built, and their inputs
    private final Map<Input, CollectedTaskInputs> inputs = new HashMap<>();

    // hashes of each file in each project, updated under lock
    private final Map<Project, Map<Path, DiskCache.CacheEntry>> currentProjectSourceHash = new HashMap<>();
    private final AtomicReference<Map<Project, Map<Path, DiskCache.CacheEntry>>> createdFilesRef = new AtomicReference<>();
    private final AtomicReference<Map<Project, Map<Path, DiskCache.CacheEntry>>> changedFilesRef = new AtomicReference<>();
    private final AtomicReference<Map<Project, Map<Path, DiskCache.CacheEntry>>> deletedFilesRef = new AtomicReference<>();

    private BlockingBuildListener prevBuild;

    private boolean incremental;

    public BuildService(TaskRegistry taskRegistry, TaskScheduler taskScheduler, DiskCache diskCache, boolean incremental) {
        this.taskRegistry = taskRegistry;
        this.taskScheduler = taskScheduler;
        this.diskCache = diskCache;
        this.diskCache.setBuildService(this);
        this.incremental = incremental;
    }

    public Map<Project, BuildMap> getBuildMaps() {
        return buildMaps;
    }

    public DiskCache getDiskCache() {
        return this.diskCache;
    }

    /**
     * Specifies a project+task that this service is responsible for, should be called once for each
     * project that will be built, with the configuration expected. This configuration will be applied
     * to all projects - if conflicting configurations need to be applied to some work, it should be
     * submitted to separate BuildServices.
     */
    public void assignProject(Project project, String finalTask, PropertyTrackingConfig.ConfigValueProvider config) {
        // find the tasks and their upstream tasks
        collectTasksFromProject(finalTask, project, config, inputs);
        // TODO this is a bit of a hack, but it's the only way to get the project's .class files
        if(incremental) {
            collectTasksFromProject(OutputTypes.BYTECODE, project, config, inputs);
        }
    }

    private void collectTasksFromProject(String taskName, Project project, PropertyTrackingConfig.ConfigValueProvider config, Map<Input, CollectedTaskInputs> collectedSoFar) {
        Input newInput = new Input(project, taskName);
        if (collectedSoFar.containsKey(newInput)) {
            // don't build a step twice
//            return collectedSoFar.get(newInput);
            return;
        }
        CollectedTaskInputs collectedInputs = new CollectedTaskInputs(project);
        if (!taskName.equals(OutputTypes.INPUT_SOURCES)) {
            PropertyTrackingConfig propertyTrackingConfig = new PropertyTrackingConfig(config);

            // build the task lambda that we'll use here
            TaskFactory taskFactory = taskRegistry.taskForOutputType(taskName);
            collectedInputs.setTaskFactory(taskFactory);
            if (taskFactory == null) {
                throw new NullPointerException("Missing task factory: " + taskName);
            }
            assert taskFactory.inputs.isEmpty();
            TaskFactory.Task task = taskFactory.resolve(project, propertyTrackingConfig);
            collectedInputs.setTask(task);
            collectedInputs.setInputs(new ArrayList<>(taskFactory.inputs));
            taskFactory.inputs.clear();

            // prevent the config object from being used incorrectly, where we can't detect its changes
            propertyTrackingConfig.close();
            collectedInputs.setUsedConfigs(propertyTrackingConfig.getUsedConfigs());
        } else {
            collectedInputs.setInputs(Collections.emptyList());
            collectedInputs.setUsedConfigs(Collections.emptyMap());
            collectedInputs.setTaskFactory(new InputSourceTaskFactory());
        }

        collectedSoFar.put(newInput, collectedInputs);

        // prep any other tasks that are needed
        for (Input input : collectedInputs.getInputs()) {

            // make sure we have sources, hashes
            if (input.getOutputType().equals(OutputTypes.INPUT_SOURCES)) {
                // stop here, we'll handle this on the fly and point it at the actual sources, current hashes
                // for jars, we unzip them as below - but requestBuild will handle reactor projects
                if (!input.getProject().hasSourcesMapped()) {
                    // unpack sources to somewhere reusable and hash contents

                    // TODO we could make this async instead of blocking, do them all at once
                    CollectedTaskInputs unpackJar = CollectedTaskInputs.jar(input.getProject());
                    BlockingBuildListener listener = new BlockingBuildListener();
                    taskScheduler.submit(Collections.singletonList(unpackJar), listener);
                    try {
                        listener.blockUntilFinished();
                        CountDownLatch latch = new CountDownLatch(1);
                        diskCache.waitForTask(unpackJar, new DiskCache.Listener() {
                            @Override
                            public void onReady(DiskCache.CacheResult result) {

                            }

                            @Override
                            public void onFailure(DiskCache.CacheResult result) {

                            }

                            @Override
                            public void onError(Throwable throwable) {

                            }

                            @Override
                            public void onSuccess(DiskCache.CacheResult result) {
                                // we know the work is done already, just grab the result dir
                                input.setCurrentContents(result.output());
                                latch.countDown();
                            }
                        });
                        latch.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Interrupted exception when unpacking!", e);
                    }
                    continue;
                } // else this is something to watch, let them get hashed automatically

            }


            collectTasksFromProject(input.getOutputType(), input.getProject(), config, collectedSoFar);
        }
    }

    /**
     * Assign the initial hashes for files in the project. Call if there is no watch service enabled.
     */
    public synchronized void initialHashes() {
        // for each project which has sources, hash them
        inputs.keySet().stream()
                .map(Input::getProject)
                .filter(Project::hasSourcesMapped)
                .distinct()
                .forEach(project -> {
                    Map<Path, DiskCache.CacheEntry> hashes = project.getSourceRoots().stream()
                            .map(Paths::get)
                            .map(DiskCache::hashContents)
                            .flatMap(Collection::stream)
                            .collect(Collectors.toMap(
                                    DiskCache.CacheEntry::getSourcePath,
                                    Function.identity(),
                                    (a, b) -> {
                                        throw new IllegalStateException("Two paths in a project had the same file " + a + ", " + b);
                                    }
                            ));
                    triggerChanges(project, hashes, Collections.emptyMap(), Collections.emptyMap());
                });
    }

    /**
     * Marks that a file has been created, deleted, or modified in the given project.
     */
    public synchronized void triggerChanges(Project project, Map<Path, DiskCache.CacheEntry> createdFiles, Map<Path, DiskCache.CacheEntry> changedFiles, Map<Path, DiskCache.CacheEntry> deletedFiles) {
        Map<Path, DiskCache.CacheEntry> hashes = currentProjectSourceHash.computeIfAbsent(project, ignore -> new HashMap<>());

        if(incremental) {
            accumulateChanges(project, createdFiles, createdFilesRef);
            accumulateChanges(project, changedFiles, changedFilesRef);
            accumulateChanges(project, deletedFiles, deletedFilesRef);

            System.out.println("created: " + createdFiles);
            System.out.println("changed: " + changedFiles);
            System.out.println("deleted: " + deletedFiles);
        }

        hashes.keySet().removeAll(deletedFiles.keySet());

        assert hashes.keySet().stream().noneMatch(createdFiles.keySet()::contains) : "File already exists, can't be added " + createdFiles.keySet() + ", " + hashes.keySet();
        hashes.putAll(createdFiles);
        assert hashes.keySet().containsAll(changedFiles.keySet()) : "File doesn't exist, can't be modified";
        hashes.putAll(changedFiles);

        // with all projects updated by this batch, we can rebuild everything -
        // callers will indicate it is time for this with requestBuild()
    }

    private void accumulateChanges(Project project, Map<Path, DiskCache.CacheEntry> files, AtomicReference<Map<Project, Map<Path, DiskCache.CacheEntry>>> filesRef) {
        filesRef.accumulateAndGet(null, (p, n) -> {
            if (p == null) {
                p = new HashMap<>();
            }
            Map<Path, DiskCache.CacheEntry> map = p.computeIfAbsent(project, k -> new HashMap<>());
            map.putAll(files);
            return p;
        });
    }

    /**
     * Only one build can take place at a time, be sure to stop the previous build before submitting a new one,
     * or the new one will have to wait until the first finishes
     * @param buildListener support for notifications about the status of the work
     * @return an object which can cancel remaining unstarted work
     */
    public synchronized Cancelable requestBuild(BuildListener buildListener) throws InterruptedException {
        // wait for the previous build, if any, to finish
        if (prevBuild != null) {
            prevBuild.blockUntilFinished();
        }

        if(incremental) {
            Map<Project, Map<Path, DiskCache.CacheEntry>> createdFiles = createdFilesRef.getAndSet(new HashMap<>());
            Map<Project, Map<Path, DiskCache.CacheEntry>> changedFiles = changedFilesRef.getAndSet(new HashMap<>());
            Map<Project, Map<Path, DiskCache.CacheEntry>> deletedFiles = deletedFilesRef.getAndSet(new HashMap<>());

            buildRequested(currentProjectSourceHash, createdFiles, changedFiles, deletedFiles);
        }
        // TODO update inputs with the hash changes we've seen
        Stream.concat(inputs.keySet().stream(), inputs.values().stream().flatMap(i -> i.getInputs().stream()))
                .filter(i -> i.getProject().hasSourcesMapped())
                .filter(i -> i.getOutputType().equals(OutputTypes.INPUT_SOURCES))
                .forEach(i -> {
                    Map<Path, DiskCache.CacheEntry> currentHashes = currentProjectSourceHash.get(i.getProject());
                    i.setCurrentContents(new TaskOutput(currentHashes.values()));
                });

        // this could possibly be more fine grained, only submit the projects which could be affected by changes
        prevBuild = new WrappedBlockingBuildListener(buildListener);
        return taskScheduler.submit(inputs.values(), prevBuild);
    }

    class WrappedBlockingBuildListener extends BlockingBuildListener {
        private final BuildListener wrapped;

        WrappedBlockingBuildListener(BuildListener wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public void onProgress(int completedCount, int startedCount, int pendingCount, String task, Project project, Activity activity) {
            wrapped.onProgress(completedCount, startedCount, pendingCount, task, project, activity);
        }

        @Override
        public void onSuccess() {
            super.onSuccess();
            wrapped.onSuccess();
        }

        @Override
        public void onFailure() {
            super.onFailure();
            wrapped.onFailure();
        }

        @Override
        public void onError(Throwable throwable) {
            super.onError(throwable);
            wrapped.onError(throwable);
        }
    }

    public BuildMap safeCreateBuildMap(Project project, Path dir,
                                       Map<Project, Map<Path, DiskCache.CacheEntry>> createdFiles,
                                       Map<Project, Map<Path, DiskCache.CacheEntry>> changedFiles,
                                       Map<Project, Map<Path, DiskCache.CacheEntry>> deletedFiles) {
        BuildMap buildMap = buildMaps.get(project);
        if (buildMap == null) {
            synchronized (buildMaps) {
                buildMap = buildMaps.get(project);
                if (buildMap == null) {
                    buildMap = createBuildMap(project, dir, createdFiles, changedFiles, deletedFiles);
                }
            }
        }

        return buildMap;
    }

    private BuildMap createBuildMap(Project project, Path dir, Map<Project, Map<Path, DiskCache.CacheEntry>> createdFiles, Map<Project, Map<Path, DiskCache.CacheEntry>> changedFiles, Map<Project, Map<Path, DiskCache.CacheEntry>> deletedFiles) {
        BuildMap buildMap;
        Map<Path, DiskCache.CacheEntry> cachedFiles = currentProjectSourceHash.get(project);
        Map<Path, DiskCache.CacheEntry> createdFilesMap = createdFiles.get(project);
        Map<Path, DiskCache.CacheEntry> changedFilesMap = changedFiles.get(project);
        Map<Path, DiskCache.CacheEntry> deletedFilesMap = deletedFiles.get(project);

        Map<String, ProjectFiles> dirToProjectFiles = new HashMap<>();

        createDirToProjectFiles(project, cachedFiles, dirToProjectFiles);

        if (createdFilesMap != null) {
            createdFilesMap.values().forEach(e -> dirToProjectFiles.get(e.getAbsoluteParent().toString())
                    .getAdded().add(e.getSourcePath().toString()));
        }

        if (changedFilesMap != null) {
            for (DiskCache.CacheEntry value : changedFilesMap.values()) {
                if (value.getSourcePath().toString().endsWith(".java")) {
                    String fileName = value.getSourcePath().toString()
                            .substring(0, value.getSourcePath().toString().lastIndexOf(".java"));
                    String buildMapFileName = fileName + ".build.map";
                    Path buildMapFile = dir.resolve("results").resolve(buildMapFileName);
                    String hash = new BuildMapHashReader(buildMapFile).hash();
                    boolean propagate = new JavaFileHashReader(value.getAbsolutePath()).hash().equals(hash);
                    //read hash from .java and compare to propagate deps
                    value.setPropagate(!propagate);
                }
            }
            changedFilesMap.values().forEach(e -> dirToProjectFiles.get(e.getAbsoluteParent().toString())
                    .getUpdated().put(e.getSourcePath().toString(), e.isPropagate()));
        }

        if (deletedFilesMap != null) {
            deletedFilesMap.values().forEach(e -> dirToProjectFiles.get(e.getAbsoluteParent().toString())
                    .getRemoved().add(e.getSourcePath().toString()));
        }

        buildMap = new BuildMap(project, dirToProjectFiles);
        buildMaps.put(project, buildMap);
        try {
            // previous execution exists, so process .dat files
            // we don't know what this module will link to, so all classes need to be cloned.
            for (com.vertispan.j2cl.build.task.Dependency dep : project.getDependencies()) {
                BuildMap depBuildMap = buildMaps.get(dep.getProject());
                Input input = new Input((Project) dep.getProject(), OutputTypes.BYTECODE);
                Path path = diskCache.getLastSuccessfulDirectory(input);
                if (depBuildMap == null && path != null) {
                    depBuildMap = safeCreateBuildMap((Project) dep.getProject(), path,
                            createdFiles, changedFiles, deletedFiles);
                }

                // Dep projects not in the sources map, will not have BuildMaps
                if (depBuildMap != null) {
                    depBuildMap.cloneToTargetBuildMap(buildMap);
                }
            }

            Input input = new Input(project, OutputTypes.BYTECODE);
            Path path = diskCache.getLastSuccessfulDirectory(input);
            buildMap.build(path);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return buildMap;
    }

    private void createDirToProjectFiles(Project project, Map<Path, DiskCache.CacheEntry> cachedFiles, Map<String, ProjectFiles> dirToProjectFiles) {

        if (cachedFiles == null || cachedFiles.isEmpty()) {
            return;
        }

        Map<String, Set<String>> dirToAll = new HashMap<>();
        project.getSourceRoots().stream().forEach(s -> {
            dirToAll.put(s, new HashSet<>());
        });

        // do not include folders.
        cachedFiles.values().stream().filter(entry -> !Files.isDirectory(entry.getAbsolutePath())).forEach(e -> {
            dirToAll.get(e.getAbsoluteParent().toString()).add(e.getSourcePath().toString());
        });

        dirToAll.entrySet().stream().forEach(e -> {
            ProjectFiles projectFiles = new ProjectFiles(e.getKey(), e.getValue());
            dirToProjectFiles.put(e.getKey(), projectFiles);
        });
    }

    public void writeFilesDat(Project project) {
        writeFileMetaData(project);
    }

    private void buildRequested(Map<Project, Map<Path, DiskCache.CacheEntry>> currentProjectSourceHash,
                                Map<Project, Map<Path, DiskCache.CacheEntry>> createdFiles,
                                Map<Project, Map<Path, DiskCache.CacheEntry>> changedFiles,
                                Map<Project, Map<Path, DiskCache.CacheEntry>> deletedFiles) {
        // make sure all BuildMaps are cleared before build starts
        System.out.println("Clear BuildMaps");
        buildMaps.clear();

        // Create BuildMaps, but only if previous build outputs exist.
        if (!diskCache.lastSuccessfulTaskDir.isEmpty()) {
            for (Project p : currentProjectSourceHash.keySet()) {
                // null check avoids re-entrance, as createBuildMap is recursive
                // and the same dep can be revisited.
                if (buildMaps.get(p) == null) {
                    Input input = new Input(p, OutputTypes.BYTECODE);
                    if (diskCache.lastSuccessfulTaskDir.containsKey(input)) {
                        safeCreateBuildMap(p, diskCache.getLastSuccessfulDirectory(input),
                                createdFiles, changedFiles, deletedFiles);
                    }
                }
            }
        }
    }

    public void copyAndDeleteFiles(Project project, String outputType, Path path) {
        Path lastPath = diskCache.lastSuccessfulTaskDir.get(new Input(project, outputType));
        if (lastPath != null) {
            copyFolder(lastPath.resolve("results").toFile(),
                    path.toFile());

            BuildMap buildMap = buildMaps.get(project);

            if (outputType.equals(OutputTypes.TRANSPILED_JS)) {
                Set<String> visited = new HashSet<>(); // don't duplicate visit .native/.java pairs
                for (String changed : buildMap.getFilesToDelete()) {
                    try {
                        if (changed.endsWith(".native.js") || changed.endsWith(".java")) {
                            // always delete .java .native pairs, regardless which was changed
                            int suffixLength = changed.endsWith(".native.js") ? 10 : 5;
                            String firstPart = changed.substring(0, changed.length() - suffixLength);
                            String binaryTypeName = firstPart.replace('/', '.');

                            if (visited.add(binaryTypeName)) {
                                if (outputType.equals(OutputTypes.STRIPPED_SOURCES)) {
                                    Path jsNativePath = path.resolve(firstPart + ".native.js");
                                    Files.deleteIfExists(jsNativePath);
                                } else {
                                    deleteInnerTypesSource(buildMap, path, binaryTypeName);
                                }
                            }
                        } else {
                            // just standard delete for anything else
                            Path changedPath = path.resolve(changed);
                            Files.deleteIfExists(changedPath);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    private void deleteInnerTypesSource(BuildMap buildMap, Path path,
                                        String binaryTypeName) throws IOException {
        int lastDotIndex = binaryTypeName.lastIndexOf('.');
        String packageName = binaryTypeName.substring(0, lastDotIndex);
        String simpleName = binaryTypeName.substring(lastDotIndex + 1);
        String firstPart = packageName.replace('.', '/') + "/" + simpleName;

        Path javaJsPath = path.resolve(firstPart + ".java.js");
        boolean b3 = Files.deleteIfExists(javaJsPath);

        Path implJavaJsPath = path.resolve(firstPart + ".impl.java.js");
        boolean b4 = Files.deleteIfExists(implJavaJsPath);

        Path jsMap = path.resolve(firstPart + ".js.map");
        boolean b5 = Files.deleteIfExists(jsMap);

        Path nativeUndrscoreJs = path.resolve(firstPart + ".native_js");
        boolean b6 = Files.deleteIfExists(nativeUndrscoreJs);
        List<String> innerTypes = buildMap.getInnerTypes(binaryTypeName);
        for (String innerType : innerTypes) {
            deleteInnerTypesSource(buildMap, path, innerType);
        }
    }

    static void copyFolder(File src, File dest) {
        try {
            FileUtils.copyDirectory(src, dest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return;
    }

    public void writeFileMetaData(Project project) {
        Path projPath = diskCache.cacheDir.toPath().resolve(project.getKey().replaceAll("[^\\-_a-zA-Z0-9.]", "-"));
        Path filesDat = projPath.resolve("files.dat");
        try (Writer out = Files.newBufferedWriter(filesDat, Charset.forName("UTF-8"))) {
            // The paths for the last outputs need to be preserved, for restart.
            String[] outputPaths = new String[]{OutputTypes.GENERATED_SOURCES, OutputTypes.STRIPPED_SOURCES, OutputTypes.STRIPPED_BYTECODE,
                    OutputTypes.BYTECODE, OutputTypes.STRIPPED_BYTECODE_HEADERS, OutputTypes.TRANSPILED_JS};
            for (String outputPath : outputPaths) {
                Path path = diskCache.lastSuccessfulTaskDir.get(new Input(project, outputPath));
                out.append(path + System.lineSeparator());
            }

            Map<Path, DiskCache.CacheEntry> cachedFiles = currentProjectSourceHash.get(project);
            Map<String, ProjectFiles> dirToProjectFiles = new HashMap<>();

            createDirToProjectFiles(project, cachedFiles, dirToProjectFiles);

            out.append(dirToProjectFiles.size() + System.lineSeparator());
            List<String> dirs = new ArrayList(dirToProjectFiles.keySet());
            Collections.sort(dirs);
            for (String dir : dirs) {
                List<String> files = new ArrayList(dirToProjectFiles.get(dir).getAll());
                Collections.sort(files);
                Path base = Paths.get(dir);
                out.append(dir + System.lineSeparator());
                out.append(files.size() + System.lineSeparator());

                //sourceDirs
                for (String file : files) {
                    Path absFile = base.resolve(file);
                    FileTime newTime = Files.getLastModifiedTime(absFile);

                    out.append(newTime.toMillis() + "," + file + System.lineSeparator());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
