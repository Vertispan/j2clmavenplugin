package net.cardosi.mojo.tools;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.j2cl.common.Problems;
import com.google.j2cl.transpiler.J2clCommandLineRunner;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//TODO factor out the wiring to set this up for reuse
public class J2cl {
    List<String> args = new ArrayList<>();
    public J2cl(List<File> strippedClasspath, File bootstrap, File jsOutDir) {
        args.add("-d");
        args.add(jsOutDir.toPath().toString());
        args.add("-cp");
        args.add(Stream.concat(Stream.of(bootstrap), strippedClasspath.stream())
                       .map(File::getAbsolutePath)
                       .collect(Collectors.joining(File.pathSeparator)));
    }

    public boolean transpile(List<Path> sourcesToCompile, List<Path> nativeSources) {
        ImmutableList.Builder<String> commandLineArgsBuilder =
                ImmutableList.<String>builder();

        commandLineArgsBuilder.addAll(sourcesToCompile.stream().map(Path::toString).collect(Collectors.toList()));

        if (!nativeSources.isEmpty()) {
            commandLineArgsBuilder.add("-nativesourcepath",
                                       nativeSources.stream().map(Path::toString)
                                                    .collect(Collectors.joining(File.pathSeparator)));
        }

        commandLineArgsBuilder.addAll(args);

        ImmutableList<String> cmdLine = commandLineArgsBuilder.build();

        int status = J2clCommandLineRunner.run(Iterables.toArray(cmdLine, String.class));
        return status == 0;

          //leaving these comments in for now, in case someone wants to inspect Problems (mdp)
//        Problems              problems;
//        ImmutableList<String> cmdLine = commandLineArgsBuilder.build();
//        //System.out.println("j2cl: " + cmdLine);
//        try {
//            problems = transpile(cmdLine);
//        } catch (Exception e) {
//            System.out.println(cmdLine);
//            throw new RuntimeException(e);
//        }
//
//        if (problems.hasErrors() || problems.hasWarnings()) {
//            problems.getErrors().forEach(System.out::println);
//            problems.getWarnings().forEach(System.out::println);
//        } else {
//           // problems.getInfoMessages().forEach(System.out::println);
//        }
//        if (problems.hasErrors()) {
//            System.out.println(cmdLine);
//        }
//        return !problems.hasErrors();
    }

    // leaving this method in for now, in case someone wants to inspect Problems (mdp)
//    private static Problems transpile(Iterable<String> args) throws Exception {
//        // J2clCommandLineRunner.run is hidden since we don't want it to be used as an entry point. As a
//        // result we use reflection here to invoke it.
//        Method transpileMethod =
//                J2clCommandLineRunner.class.getDeclaredMethod("runForTest", String[].class);
//        transpileMethod.setAccessible(true);
//        return (Problems) transpileMethod.invoke(null, (Object) Iterables.toArray(args, String.class));
//    }
}
