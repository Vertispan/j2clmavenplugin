J2CL Maven plugin
=================

This plugins compiles Java sources to optimized JavaScript using https://github.com/google/j2cl/ and
https://github.com/google/closure-compiler/. All Java code in this project will be transpiled to JS, 
and any source in dependencies will be transpiled as well, and all of that JS will then be optimized
with the closure-compiler to produce small, efficient JavaScript.

Webjars that are included in the project's list of runtime dependencies will be made available in the
compile output, placed relative to the initial script's output directory.

Resources present in a `public/` directory within normal Java packages will also be copied to the
output directory.

All other JS found in Java packages will be assumed to be JavaScript that should be included in the
main build output, and is assumed to be safe to compile with closure.

# Example usage

A fully working sample project can be found at https://github.com/treblereel/j2cl-tests. Also consider the
[Maven Archetypes](j2cl-archetypes/README.md) that are developed in this project, or check out the [integration
tests](j2cl-maven-plugin/src/it/) used to verify various aspects of the project each build, especially
[hello-world-single](j2cl-maven-plugin/src/it/hello-world-single) and
[hello-world-reactor](j2cl-maven-plugin/src/it/hello-world-reactor).

# Goals

The plugin has four goals

1. `build`: executes a single compilation, typically to produce a JS application or library. Bound by 
default to the `prepare-package` phase.

2. `test`: compiles and executes j2cl-annotated tests. Bound by default to the `test` phase.

3. `watch`: monitor source directories, and when changes happen that affect any `build` or `test`, recompile the 
required parts of the project. While this can be run on an individual client project, it is designed to run
on an entire reactor at once from the parent project, where it will notice changes from any project required by
the actual client projects, and can be directed to generate output anywhere that the server will notice and
serve it.
  
<!--   * `watch-test`: only rebuild things that affect `test` executions, useful when iterating on tests and avoiding
    building the application itself
   * `watch-build`: only rebuild things that affect `build` executions, may save time if tests aren't currently
    being re-run               -->
  
4. `clean`: cleans up all the plugin-specific directories.


----

Building executable JS output for a "GWT 3" project requires transpiling each of its dependencies
with J2CL, then combining them all into a single output through the use of the closure compiler.
Along the way, we must also take care to preprocess sources (strip out any `@GwtIncompatible`-annotated
members) and run any annotations processors. 

Building once from sources is simple enough - for each item in the dependency graph, first build the 
projects it depends on, then build it - preprocess sources, to `@GwtIncompatible`-stripped bytecode while 
running annotation processors, then using stripped bytecode of dependencies, transpile any java sources
in the project (both generated and provided by the project). This can even be cached to a large degree:
create a hash of
 * the toolchain (j2cl, maven plugin)
 * the contents of the source files
 * the hashes of the dependencies
 
If any of those changes, we know we likely need to recompile a given project, and then the projects that 
depend on it, etc.

For "dev mode", we want to keep these processes running, keep as much cached and jit'd as possible, to
prevent spending startup time over and over on each project. If we execute a single maven goal to do this,
we will want it to be run on a "parent" in the reactor, so that any changed modules are detected and
recompiled correctly, rather than depending on stale sources.

----

## Persistent, shared caching

To help make this faster on your own machine, you can move the cache directory out of `target/`, and into
somewhere global like `~/.m2/` so that it doesn't get deleted every time you need to clean your maven project.
Similarly, this will result in all GWT 3 projects built on your machine sharing the same cache, so that as long
as the same version of the compiler is run on the same sources, output can be reused rather than building it
again.

To do this, in your `~/.m2/settings.xml` file, you can add a profile like this:
```xml
      <profile>
        <id>shared-gwt-cache</id>
        <activation>
          <activeByDefault>true</activeByDefault>
        </activation>
        <properties>
          <gwt3.cache.dir>/home/myusername/.m2/gwt3BuildCache</gwt3.cache.dir>
        </properties>
      </profile>
```

Since compiled output is stored based on the hash of the inputs, this should be safe, but from time to time
you may find the need to remove some cache entries. There are a few tools for this:
 
 * `mvn j2cl:clean` - this will find all the artifacts in the current reactor project and remove any cache entries
 found in the specified directory. 
 * `mvn j2cl:clean -Dartifact=some-artifact-id` - deletes any artifact that was built with this plugin from the
 cache which has an artifactId matching the given parameter. Currently does not support a groupId in the name
 of making it easier to quickly specify a cache entry, and to make the contents of the cache directory slightly
 easier to traverse manually.
 * `mvn j2cl:clean -Dartifact=*` - deletes all contents in the cache directory. If you find yourself doing this 
 a lot, file a bug describing whatever is going wrong frequently, and consider leaving the cache directory in the
 target directory where it defaults to, so that it can be cleaned automatically.
 
----
 
## Build process

This section is in somewhat reverse order, as each step in the build process is requested by some other step
which already tried to run, and found that one or more of its dependencies wasn't complete. This means that
not every step listed here actually runs across the entire dependency tree, which lets us not only save a small
amount of time, but also avoid some impossible to compile code.

There are many places where Maven hurts rather than helps this process, but understand that this plugin exists
not because maven is the best option for building any and all projects, but because it is a widely used, widely
supported option that many teams have held on to for years. Gradle's first release was 2007, Maven's was 2004, 
but even by the time GWT 2 shipped in 2009, overhauling many aspects of GWT's tooling, Ant was still apparently 
considered to be the best option available. This doesn't mean that Ant was the superior option, just that reducing
friction counts for a lot to get good tools into the hands of developers. In some places in this section, Maven's
shortcomings will be directly pointed out, but some may have been omitted due to brevity. Nevertheless, at the
time of writing, this plugin remains the only way to build general J2CL projects outside of Bazel, and even
including Bazel, the only way to run tests in J2CL.


### Compute Hash
Each set of sources in the build is hashed - the contents of the artifact or the source directory is hashed, along
with the version of the plugin, and the hash of each of its dependencies. This means
 * if the plugin version changes, everything will recompile
 * if an "upstream" dependency is updated, everything that relies on it in some way will recompile
 * if some dependency reverts to some previous state, old cache entries may be available for reuse

Test sources are treated as if they are a distinct project, depending on the original project as well as all
of the test dependencies, so that changing tests never affect the project itself, though changing the project's
main sources does change the hash of the tests.

Dependencies are examined at each node in the graph here, individually, meaning that if an application `App` depends
on a library `LibA`, which depends on some GWT-incompatible library `LibB`, it isn't necessarily sufficient for 
`App` to add an `<exclusion>` entry to `LibA`, specifying that `LibB` shouldn't be compiled. That exclusion will
indeed prevent `LibB` from being added to `App`'s dependencies, but at some point we still need to compile `LibA`.

For this, there is plugin-specific mechanism, a global replacement directive which will modify the dependency graph
anywhere one of the specified dependencies is found. By default, this replaces two different `jsinterop-base`
groupId:artifactId pairs with `com.vertispan.jsinterop:base`, which is _only_ j2cl compatible (and backward 
compatible to at least 1.0.0-RC1), and removes gwt-dev, gwt-servlet, and gwt-user outright, with no replacements.

As this replacement tool affects the dependencies that a given set of sources has, it naturally changes the has as
well. This means that two different applications being compiled, with the same set of dependencies, but with
different replacement rules, might well share little or none of the cache with the other. 


### Generate Sources
For any set of sources which is part of the current reactor, sources will be generated - for source jars already
present in the local m2 cache or some repository, it is assumed that, following standard maven conventions, these
are already generated. This step does not delegate to maven-compiler-plugin, as there doesn't appear to be a sane
way to do this in cases like Dev Mode. The outputs of this step are later treated as part of the provided sources,
though this step does not affect the hash which has already been computed.

Note that this step uses the built-in `JavaCompiler`, and that the classpath provided to the compiler is the default
Maven-generated set of dependencies. It is assumed that any processor which is aware of GWT yet generates code
that is incompatible for GWT will annotate appropriately with `@GwtIncompatible`. The alternative would be to re-run
all annotation processors after stripping bytecode, which would require running on all artifacts from remote
repositories as well.

Reactor projects all have their generated content and bytecode built into a single directory, alongside original
sources, resources, and bytecode. This results in a directory that should reflect accurately unpacking a jar
which happens to include its own sources.


### Strip `@GwtIncompatible`
The J2CL-provided `JavaPreprocessor` class is used to process all sources before they are compiled, stripping out
any members which are marked with this annotation. This results in a new directory of sources, which may be 
exactly identical to the originals, or may have small differences from their `@GwtIncompatible`-decorated elements
being replaced by whitespace (the behavior of this class, at last check, so as to preserve line/column numbers as
precisely as possible).


### Compile stripped sources to bytecode
Later steps such as compiling the stripped sources to Closure-compatible JavaScript need a classpath, which may
require that we provide stripped bytecode as well. One option could be to just process the bytecode directly and
remove annotated members, but we've elected not to do that here, since we actually have the sources and can compile
them.

This requires running `JavaCompiler` again, this time on the stripped sources. For the classpath, the current project's
dependencies are all required to have already generated their own stripped bytecode. In Maven terms, we use the 
"compile+runtime" scope for this step, due to an inconsistency between Maven and Gradle: Maven provides for several
scopes that describe what the current project will use the sources for, while Gradle-generate pom files prefer to
describe what downstream projects will use it for. In short, Maven's "compile" scope is too broad for Gradle, as it
indicates not only that a given dependency is needed to compile the project's sources, but also that it is needed
to compile some project which uses this project's output. Gradle instead may emit `scope=runtime` for this case,
only indicating that in order to run this project, the dependency will be required, and offering no clues for how
one might recompile the sources (say, for example, after sources have been stripped of `@GwtIncompatible`).

The J2cl-provided jre.jar is also included here automatically, as are jsinterop-annotations and some "internal"
annotations. In the case of `j2cl:test` being executed, the jar containing `@J2clTestInput` annotation processor is 
included as well.


### Compile stripped sources to Closure-compatible JavaScript
Finally we produce some JavaScript, via the `J2clTranspiler` itself. Given a set of sources (and any `.native.js` files)
and the "compile+runtime" bytecode classpath, we generate JavaScript sources. Any `.js` files in the source directories
(including generated source directories) which do not end in `.native.js` are copied at this time to the same location 
as the rest of the J2cl output.

As above, the classpath is augmented with other J2cl provided bytecode jars.

Notably, if a source set doesn't actually contain any `.java` files, J2CL isn't run (as `J2clTranspiler` fails with
a non-obvious error message), but any source `.js` files are still copied, allowing projects to potentially consist
only of JS sources for use in the eventual output JS.


### Bundle/Compile JavaScript with Closure Compiler
This step defaults to using only the "runtime" (or "test", in the case of tests) dependencies of a given project. The
JavaScript sources of each of these are passed to Closure Compiler, along with some built-in JS inputs, such as the
J2cl-provided JRE emulation, and a subset of the Closure Library. Configuration options specified in the plugin 
configuration will be passed to Closure Compiler when it is invoked:

 * externs, which should not be included as sources, but indicate that these APIs will be provided by some other source
 that the Closure Compiler isn't otherwise aware of.
 * defines, allowing `System.getProperty` to return those values (note that if none are set, there are some defaults
 which will disable or reduce various runtime checks which would slow or bloat optimized applications).
 * entrypoints, which tell the Closure Compiler where to start its tree-shaking and other optimizations from.


## Dev Mode
Given the above build process, each source directory in the current reactor is watched for changes. If a change occurs,
that dependency is marked as "dirty", as are all source sets which depend on it, recursively. If this process ends up
hitting an application or test which would be compiled or bundled with the Closure Compiler, that task is kicked off,
requiring that any other necessary work is done (or other cache entries used, if any).