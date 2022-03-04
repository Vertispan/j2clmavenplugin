# J2cl Maven Plugin

This plugins compiles Java sources to optimized JavaScript using https://github.com/google/j2cl/ and
https://github.com/google/closure-compiler/. All Java code in this project will be transpiled to JS,
and any source in dependencies will be transpiled as well, and all of that JS will then be optimized
with the closure-compiler to produce small, efficient JavaScript.

* Goals Overview

    * [j2cl:build](build-mojo.html) Translates Java into optimized JS.
    * [j2cl:watch](watch-mojo.html) Watches for changes in the current project(s), and translates Java to JS when changes occur.
    * [j2cl:test](test-mojo.html) Runs JUnit tests as closure-library test suites.
    * [j2cl:clean](clean-mojo.html) Deletes the plugin's own build cache.

* Usage

  See [the usage page](usage.html) for a description of how to integrate this plugin into an existing project, or check 
  out the examples below for already-working samples to explore. Join us for discussion at https://gitter.im/vertispan/j2cl
  or https://github.com/Vertispan/j2clmavenplugin/discussions to ask questions and make suggestions as we continue to 
  develop this plugin.

* Examples

  A fully working sample project can be found at https://github.com/treblereel/j2cl-tests. Also consider the
  [Maven Archetypes](https://github.com/Vertispan/j2clmavenplugin/tree/main/j2cl-archetypes/README.md) that are 
  developed in this project, or check out the [integration tests](https://github.com/Vertispan/j2clmavenplugin/tree/main/j2cl-maven-plugin/src/it)
  used to verify various aspects of the project each build, especially
  [hello-world-single](https://github.com/Vertispan/j2clmavenplugin/tree/main/j2cl-maven-plugin/src/it/hello-world-single) and
  [hello-world-reactor](https://github.com/Vertispan/j2clmavenplugin/tree/main/j2cl-maven-plugin/src/it/hello-world-reactor).
