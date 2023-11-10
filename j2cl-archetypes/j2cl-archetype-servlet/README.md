# `j2cl-archetype-servlet`

```
mvn archetype:generate -DarchetypeGroupId=com.vertispan.j2cl.archetypes \
-DarchetypeArtifactId=j2cl-archetype-servlet \
-DarchetypeVersion=0.22.0
```

Creates two Java modules, one for the server, and one for the client. The server module uses Jakarta
servlets to host some http static content and offer some services, and the client module provides a
simple J2cl-based browser application that is served from, and connects to that server for more content.
The parent pom is configured to make it easy to add either shared or client-only modules to the project,
and still get a good debugging experience from the browser.

After creating this, you'll need two consoles to start it. First, run `mvn j2cl:watch` in one
console - as soon as it has started to do any work in the j2cl-maven-plugin, it is safe to run
the next command. In the other console, run `mvn jetty:run  -pl *-server -am -Denv=dev`. Once
Jetty has logged `Started Jetty Server` and J2cl has logged `Build Complete: ready for browser refresh`,
open `http://localhost:8080/` in a browser to see the app. Refresh the page in the browser after
editing any source file (and seeing the "Build Complete" message in the j2cl:watch log).

The `j2cl:watch` goal must be started before `jetty:run` so that the j2cl output directory exists -
if that directory is changed in the root pom, this order may not strictly be necessary.

To deploy a sample to a servlet container, use `mvn verify` to build a war, then copy it from the
`target/` directory to the webapps directory of the servlet container.
