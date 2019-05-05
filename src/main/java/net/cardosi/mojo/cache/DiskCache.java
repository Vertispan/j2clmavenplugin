package net.cardosi.mojo.cache;

import com.google.j2cl.frontend.FrontendUtils;
import net.cardosi.mojo.Hash;
import net.cardosi.mojo.tools.GwtIncompatiblePreprocessor;
import net.cardosi.mojo.tools.Javac;
import org.apache.maven.artifact.ArtifactUtils;

import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DiskCache {
    private static final PathMatcher javaMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");

    private final ExecutorService s = Executors.newFixedThreadPool(1);//TODO make this configurable, confirm it is actually thread-safe

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final String pluginVersion;
    private final File jsZipCacheDir;

    private final File bootstrap;
    private final List<File> extraClasspath;

    public DiskCache(String pluginVersion, File jsZipCacheDir, File bootstrap, List<File> extraClasspath) {

        this.pluginVersion = pluginVersion;
        this.bootstrap = bootstrap;
        this.extraClasspath = extraClasspath;

        this.jsZipCacheDir = new File(jsZipCacheDir, pluginVersion);
        this.jsZipCacheDir.mkdirs();
    }

    public void takeLock() {
        lock.writeLock().lock();
    }
    public void release() {
        lock.writeLock().unlock();
    }


    public void submit(CachedProject cachedProject) {
        if (cachedProject.getChildren().isEmpty()) {
//            System.out.println("no dependencies, compiling right away " + cachedProject.getArtifactKey());
            s.submit(() -> compile(cachedProject));
        }

        // once all dependents are compiled, then submit ourselves
        CompletableFuture.allOf(cachedProject.getChildren().stream()
                .map(CachedProject::getCompiledOutput)
                .toArray(CompletableFuture[]::new)
        ).handleAsync((success, failure) -> {
            if (failure != null) {
                // at least one of the above failed, so don't actually compile this project, just leave it
                // until all upstreams are ready to go. If we marked the project as failed, it would in turn
                // mark its downstreams as failed accordingly, when causes a lot of noise in the log and
                // hides what wen't wrong
                System.out.println("Cannot compile " + cachedProject + " due to an earlier failure");
//                failure.printStackTrace();
            } else {
                compile(cachedProject);
            }

            return null;
        }, s);

    }

    private void compile(CachedProject project) {
        lock.readLock().lock();
        try {
//            System.out.println("Starting compile for " + project.getArtifactKey() + " dirty:" + project.isDirty());

            //build compile classpaths, compute the hash
            //TODO filter to scope=compile
            Hash hash = new Hash();
            hash.append(pluginVersion.getBytes(Charset.forName("UTF-8")));


            List<File> plainClasspath = new ArrayList<>(extraClasspath);
            List<File> strippedClasspath = new ArrayList<>(extraClasspath);
            for (CachedProject child : project.getChildren()) {
                TranspiledCacheEntry entry = child.getCompiledOutput().getNow(null);
                if (entry == null) {
                    // not ready, resubmit
                    submit(project);
                    System.out.println("Dependency not ready! " + project);
                    return;
                }
                File dir = new File(jsZipCacheDir, entry.getHash() + "-" + entry.getArtifactId());
                strippedClasspath.add(new File(dir, "stripped-bytecode"));
                File bytecodeDir = new File(dir, "bytecode");
                if (bytecodeDir.exists()) {
                    plainClasspath.add(bytecodeDir);
                } else {
                    plainClasspath.add(child.getArtifact().getFile());
                }
                hash.append(entry.getHash().getBytes(Charset.forName("UTF-8")));
            }

            //finish the hash from our sources
            // try source roots first, then check for a sources jar if required
            try {
                if (!project.getMavenProject().getCompileSourceRoots().isEmpty()) {
                    for (String compileSourceRoot : project.getMavenProject().getCompileSourceRoots()) {
                        appendHashOfAllSources(hash, Paths.get(compileSourceRoot));
                    }
                } else {
                    try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + project.getArtifact().getFile().toURI()), Collections.emptyMap())) {
                        for (Path rootDirectory : zip.getRootDirectories()) {
                            appendHashOfAllSources(hash, rootDirectory);
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                project.getCompiledOutput().completeExceptionally(e);
            }

//            System.out.println(project.getArtifact() + " has hash " + hash);
            File cacheDir = new File(jsZipCacheDir, hash.toString() + "-" + project.getArtifactId());

            File completeMarker = new File(cacheDir, "complete");
            File failedMarker = new File(cacheDir, "failed");

            //if it exists, return it when ready
            if (!cacheDir.mkdir()) {
                // if the directory already exists, then someone else has already started on this piece, wait for the
                // complete marker to exist.
                // TODO timeout?
                Path cacheDirPath = Paths.get(cacheDir.toURI());
                try (WatchService w = cacheDirPath.getFileSystem().newWatchService()) {
                    cacheDirPath.register(w, StandardWatchEventKinds.ENTRY_CREATE);
                    // first check to see if it exists, then wait for next event to occur
                    do {
                        if (completeMarker.exists()) {
//                            System.out.println("already ready " + project.getArtifactKey());
                            project.getCompiledOutput().complete(new TranspiledCacheEntry(hash.toString(), project.getArtifactId()));
                            return;
                        }
                        if (failedMarker.exists()) {
                            System.out.println("compilation failed in some other thread/process " + project.getArtifactKey() + " " + hash);
                            project.getCompiledOutput().completeExceptionally(new IllegalStateException("Compilation failed in another thread/process"));
                            return;
                        }
                        System.out.println("Waiting 10s and then checking again if other thread/process finished " + project.getArtifactKey());
                        w.poll(10, TimeUnit.SECONDS);
                    } while (true);
                } catch (IOException | InterruptedException ex) {
                    System.out.println("Error while waiting for external thread/process");
                    ex.printStackTrace();
                    project.getCompiledOutput().completeExceptionally(ex);
                    return;
                }
            }

            try {
                // steps to transpile a given project
                // 0. if in the form of a reactor project, proceed to 1, otherwise skip ahead to 2
                //    if there is no @GwtIncompatible, either could skip to 3
                //    if there are no processors, could also skip this
                List<FrontendUtils.FileInfo> sourcesToStrip = new ArrayList<>();
//        if (project.hasGwtIncompatible()) {
//            sources = TODO
//        } else ...

                if (project.hasSourcesMapped()) {
                    // 1. javac the sources, causing annotation processors to run. This uses the entire compile classpath of the
                    //    project, we just need to generate sources as would happen in the orig project
                    File annotationSources = new File(cacheDir, "annotation-sources");
                    annotationSources.mkdirs();
                    File plainBytecode = new File(cacheDir, "bytecode");
                    plainBytecode.mkdirs();
                    Javac javac = new Javac(annotationSources, plainClasspath, plainBytecode, bootstrap);
                    List<FrontendUtils.FileInfo> sources = project.getMavenProject().getCompileSourceRoots().stream().flatMap(dir -> getFileInfoInDir(Paths.get(dir), javaMatcher).stream()).collect(Collectors.toList());
                    if (sources.isEmpty()) {
                        Files.createFile(completeMarker.toPath());
                        project.getCompiledOutput().complete(new TranspiledCacheEntry(hash.toString(), project.getArtifactId()));
                        return;
                    }
//                    System.out.println("step 1 " + project.getArtifactKey());
                    if (!javac.compile(sources)) {
                        // so far at least we don't have any whitelist need here, it wouldnt really make sense to let a
                        // local compile fail
                        throw new IllegalStateException("javac failed, check log");
                    }
                    sourcesToStrip.addAll(getFileInfoInDir(Paths.get(annotationSources.toURI()), javaMatcher));
                    sourcesToStrip.addAll(project.getMavenProject().getCompileSourceRoots().stream()
                            .map(p -> Paths.get(p))
                            .flatMap(p -> getFileInfoInDir(p, javaMatcher).stream())
                            .collect(Collectors.toList()));
                } else {
                    //unpack the jar's sources
                    File sources = new File(cacheDir, "unpacked-sources");

                    //collect sources from jar instead
                    try (ZipFile zipInputFile = new ZipFile(project.getArtifact().getFile())) {
                        for (ZipEntry entry : Collections.list(zipInputFile.entries())) {
                            if (entry.isDirectory()) {
                                continue;
                            }
                            try (InputStream inputStream = zipInputFile.getInputStream(entry)) {
                                Path outPath = sources.toPath().resolve(entry.getName());
                                Files.createDirectories(outPath.getParent());
                                Files.copy(inputStream, outPath);
                                sourcesToStrip.add(FrontendUtils.FileInfo.create(outPath.toString(), outPath.toString()));
                            }
                        }
                    }
                }
                // 2. run the GwtIncompatible-stripper on the sources, both the provided ones and the generated ones
                File strippedSources = new File(cacheDir, "stripped");
                strippedSources.mkdirs();
                GwtIncompatiblePreprocessor stripper = new GwtIncompatiblePreprocessor(strippedSources);
//                System.out.println("step 2 " + project.getArtifactKey());
                stripper.preprocess(sourcesToStrip);
                // 3. javac stripped sources with a classpath of other stripped bytecode, we'll add this stripped bytecode
                //    result to the classpath of step 3 and 4 of other projects. Do not rerun apt this time.
                File strippedBytecode = new File(cacheDir, "stripped-bytecode");
                strippedBytecode.mkdirs();
                Javac javac = new Javac(null, strippedClasspath, strippedBytecode, bootstrap);
                List<FrontendUtils.FileInfo> sourcesToCompile = getFileInfoInDir(Paths.get(strippedSources.toURI()), javaMatcher);
                if (sourcesToCompile.isEmpty()) {
                    Files.createFile(completeMarker.toPath());
                    project.getCompiledOutput().complete(new TranspiledCacheEntry(hash.toString(), project.getArtifactId()));
                    return;
                }
//                System.out.println("step 3 " + project.getArtifactKey());
                boolean success = javac.compile(sourcesToCompile);
                if (!success) {
                    if (!project.isIgnoreJavacFailure()) {
                        throw new IllegalStateException("javac failed, check log for details");
                    }
                }
                // 4. j2cl stripped sources with a classpath of other stripped bytecode
                // TODO support not running the last step if we won't need it in this run, but mark the on-disk cache
                //      accordingly so we can rebuild if it is needed later
//        J2cl j2cl = new J2cl();
//        j2cl
                // 5. copy other resources into the js dir


                //mark as done
//                System.out.println("success " + project.getArtifactKey());
                Files.createFile(completeMarker.toPath());
                project.getCompiledOutput().complete(new TranspiledCacheEntry(hash.toString(), project.getArtifactId()));
            } catch (Throwable ex) {
                System.out.println("failure in " + ArtifactUtils.key(project.getArtifact()));
                ex.printStackTrace();
                try {
                    Files.createFile(failedMarker.toPath());
//                    FileUtils.deleteDirectory(cacheDir);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                project.getCompiledOutput().completeExceptionally(ex);
            }

        } finally {
            lock.readLock().unlock();
        }
    }

    private List<FrontendUtils.FileInfo> getFileInfoInDir(Path dir, PathMatcher matcher) {
        try {
            return Files.find(dir, Integer.MAX_VALUE, ((path, basicFileAttributes) -> matcher.matches(path)))
                    .map(p -> FrontendUtils.FileInfo.create(p.toString(), p.toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
}
