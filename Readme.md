J2CL Maven plugin
=================

This plugin includes the code original developed as

        com.vertispan.j2cl:build-tools

built from here:

    https://github.com/gitgabrio/j2cl-devmode-strawman

------------------------
The plugin has four goals

1. `build`: executes a single compilation, typically to produce a JS application or library.

2. `test`: compiles and executes j2cl-annotated tests, once.

3. `watch`: monitor source directories, and when changes happen that affect any `build` or `test`, recompile the 
required parts of the project
  
   * `watch-test`: only rebuild things that affect `test` executions, useful when iterating on tests and avoiding
    building the application itself
   * `watch-build`: only rebuild things that affect `build` executions, may save time if tests aren't currently
    being re-run 
  
4. clean: it cleans up all the plugin-specific directories


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