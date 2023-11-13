# `j2cl-archetype-simple`

```
mvn archetype:generate -DarchetypeGroupId=com.vertispan.j2cl.archetypes \
-DarchetypeArtifactId=j2cl-archetype-simple \
-DarchetypeVersion=0.22.0
```

This project is a simple html page, with a css file, and a single Java class. It is _not_ a good
example of how to set up a client/server project, but serves only to show how to make very simple
standalone samples.

After creating this, you'll need two consoles to start it. In one window, run `mvn jetty:run` -
you'll know it is working when it reports "Started Jetty Server". In the other console window, run
`mvn j2cl:watch`, and wait for `Build Complete: ready for browser refresh`. With both running, open
`http://localhost:8080/` in a browser to see the app. Refresh the page in the browser after editing
any source file (and seeing the "Build Complete" message in the j2cl:watch log). The order of the
commands is not important, and it is possible to start both at the same time, but both should be
finished before trying to use the app.

To deploy a sample to a servlet container, use `mvn verify` to build a war, then copy it from the
`target/` directory to the webapps directory of the servlet container. Alternatively, just run
`mvn jetty:run` without also running the watch command, the j2cl output will be replaced automatically
with JS compiled for production.

To reiterate, this is _not_ a suggested way to develop a real project, but is only intended for a quick
sample to get the basic idea of how the plugin functions, and to quickly experiment with J2CL and the
Closure Compiler in Maven.
