J2CL Maven plugin
=================

This plugin includes the code original developed as

        com.vertispan.j2cl:build-tools

built from here:

    https://github.com/gitgabrio/j2cl-devmode-strawman

------------------------
The plugin has four goals

1. build: it executes a single compilation

2. run: it starts in listening mode detecting file changing and eventually recompiling them

3. clean: it cleans up all the plugin-specific directories

4. test: compiles and executes j2cl-annotated tests, once

----------------------
To test it:

1 clone https://github.com/gitgabrio/connected

2 switch to branch j2cl-mavenplugin

3 issue mvn package -Pdevmode

The connected project has been modified so that

1 all the dependencies and compiled classes/js ends up inside target/webapp

2 the jetty server listen for modification and serves target/webapp



----

Building executable JS output for a GWT 3 project requires transpiling each of its dependencies
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
recompiled correctly, rather than depending on stale sources