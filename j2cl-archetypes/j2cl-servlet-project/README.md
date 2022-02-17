# `j2cl-servlet-project`

Creates two Java modules, one for the server, and one for the client. The server module uses Jakarta
servlets to host some http static content and offer some services, and the client module provides a
simple J2cl-based browser application that is served from, and connects to that server for more content.
The parent pom is configured to make it easy to add either shared or client-only modules to the project,
and still get a good debugging experience from the browser.

After creating this, you'll need two consoles to start it. First, run `mvn jetty:run  -pl *-server -am -Denv=dev` 
in one window - you'll know it is working when it reports "Started Jetty Server". In the other 
console window, run `mvn j2cl:watch`, and wait for `Build Complete: ready for browser refresh`. With 
both running, open `http://localhost:8080/` in a browser to see the app. Refresh the page in the 
browser after editing any source file (and seeing the "Build Complete" message in the j2cl:watch log).

