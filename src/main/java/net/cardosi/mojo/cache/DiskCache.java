package net.cardosi.mojo.cache;

import com.google.j2cl.frontend.FrontendUtils;
import net.cardosi.mojo.Hash;
import net.cardosi.mojo.tools.GwtIncompatiblePreprocessor;
import net.cardosi.mojo.tools.Javac;
import org.apache.commons.io.FileUtils;

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
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DiskCache {
    private static final PathMatcher javaMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");

    private final ExecutorService s = Executors.newFixedThreadPool(1);

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
            System.out.println("no dependencies " + cachedProject.getArtifactId());
            s.submit(() -> compile(cachedProject));
        }

        // once all dependents are compiled, then submit ourselves
        CompletableFuture.allOf(cachedProject.getChildren().stream()
                .map(CachedProject::getCompiledOutput)
                .toArray(CompletableFuture[]::new)
        ).thenAcceptAsync(ignore -> compile(cachedProject), s);

    }

    private void compile(CachedProject project) {
        lock.readLock().lock();
        try {
//            System.out.println("Starting compile for " + project);

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

            System.out.println(project.getArtifact() + " has hash " + hash);
            File cacheDir = new File(jsZipCacheDir, hash.toString() + "-" + project.getArtifactId());

            //if it exists and is complete, return it right away
            if (cacheDir.exists()) {
                System.out.println("already ready " + project.getArtifactId());
                project.getCompiledOutput().complete(new TranspiledCacheEntry(hash.toString(), project.getArtifactId()));
                return;
            }
            //for debugging just delete so it can all be recreated
//            try {
//                FileUtils.deleteDirectory(cacheDir);
//            } catch (IOException e) {
//                e.printStackTrace();
//            }

            //else, lock it
            //TODO

            cacheDir.mkdirs();


            try {
                // steps to transpile a given project
                // 0. if in the form of a reactor project, proceed to 1, otherwise skip ahead to 2
                //    if there is no @GwtIncompatible, either could skip to 3
                //    if there are no processors, could also skip this
                List<FrontendUtils.FileInfo> sourcesToStrip = new ArrayList<>();
//        if (project.hasGwtIncompatible()) {
//            sources = TODO
//        } else ...

                if (!project.getMavenProject().getCompileSourceRoots().isEmpty()) {
                    // 1. javac the sources, causing annotation processors to run. This uses the entire compile classpath of the
                    //    project, we just need to generate sources as would happen in the orig project
                    File annotationSources = new File(cacheDir, "annotationSources");
                    annotationSources.mkdirs();
                    File plainBytecode = new File(cacheDir, "bytecode");
                    plainBytecode.mkdirs();
                    Javac javac = new Javac(annotationSources, plainClasspath, plainBytecode, bootstrap);
                    List<FrontendUtils.FileInfo> sources = project.getMavenProject().getCompileSourceRoots().stream().flatMap(dir -> getFileInfoInDir(Paths.get(dir), javaMatcher).stream()).collect(Collectors.toList());
                    if (sources.isEmpty()) {
                        project.getCompiledOutput().complete(new TranspiledCacheEntry(hash.toString(), project.getArtifactId()));
                        return;
                    }
                    System.out.println("step 1 " + project.getArtifactId());
                    if (!javac.compile(sources)) {
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
//                System.out.println("step 2 " + project.getArtifactId());
                stripper.preprocess(sourcesToStrip);
                // 3. javac stripped sources with a classpath of other stripped bytecode, we'll add this stripped bytecode
                //    result to the classpath of step 3 and 4 of other projects. Do not rerun apt this time.
                File strippedBytecode = new File(cacheDir, "stripped-bytecode");
                strippedBytecode.mkdirs();
                Javac javac = new Javac(null, strippedClasspath, strippedBytecode, bootstrap);
                List<FrontendUtils.FileInfo> sourcesToCompile = getFileInfoInDir(Paths.get(strippedSources.toURI()), javaMatcher);
                if (sourcesToCompile.isEmpty()) {
                    project.getCompiledOutput().complete(new TranspiledCacheEntry(hash.toString(), project.getArtifactId()));
                    return;
                }
//                System.out.println("step 3 " + project.getArtifactId());
                javac.compile(sourcesToCompile);
                // 4. j2cl stripped sources with a classpath of other stripped bytecode
                // TODO support not running the last step if we won't need it in this run, but mark the on-disk cache
                //      accordingly so we can rebuild if it is needed later
//        J2cl j2cl = new J2cl();
//        j2cl
                // 5. copy other resources into the js dir


                //mark as done
                System.out.println("success " + project.getArtifactId());
                project.getCompiledOutput().complete(new TranspiledCacheEntry(hash.toString(), project.getArtifactId()));
            } catch (Throwable ex) {
                System.out.println("failure " + project.getArtifactId());
                ex.printStackTrace();
                try {
                    FileUtils.deleteDirectory(cacheDir);
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


    public void watch() {
        try {
            Thread.sleep(60 * 60 * 1_000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
