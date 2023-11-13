# J2CL Maven Project Archetypes

All archetypes take the usual `artifactId`, `groupId`, `version`, and `package` and also `module` to
use for naming some classes in the generated projects.

To create a project interactively, use:
```
mvn archetype:generate -DarchetypeGroupId=com.vertispan.j2cl.archetypes \
-DarchetypeArtifactId=<archetype-name> \
-DarchetypeVersion=0.22.0
```

To specify these four variables, add them as system properties:
```
mvn archetype:generate -DarchetypeGroupId=com.vertispan.j2cl.archetypes \
-DarchetypeArtifactId=<archetype-name> \
-DarchetypeVersion=0.22.0 \
-DgroupId=my.project.group.id \
-DartifactId=myapp \
-Dversion=1.0-SNAPSHOT \
-Dmodule=MyApp
```

Tip: if you don't have a pre-installed `j2cl-maven-plugin`, you can install it manually, taking care
to replace `<archetype-name>` with the name of the archetype:
```
mvn org.apache.maven.plugins:maven-dependency-plugin:get \
-DrepoUrl=https://repo.vertispan.com/j2cl/ \
-Dartifact=com.vertispan.j2cl.archetypes:<archetype-name>:0.22.0
```

# [`j2cl-archetype-simple`](j2cl-archetype-simple)

This project is a simple html page, with a css file, and a single Java class. It is _not_ a good 
example of how to set up a client/server project, but serves only to show how to make very simple
standalone samples.

# [`j2cl-archetype-servlet`](j2cl-archetype-servlet)

Creates two Java modules, one for the server, and one for the client. The server module uses Jakarta
servlets to host some http static content and offer some services, and the client module provides a
simple J2cl-based browser application that is served from, and connects to that server for more content.
The parent pom is configured to make it easy to add either shared or client-only modules to the project,
and still get a good debugging experience from the browser.
