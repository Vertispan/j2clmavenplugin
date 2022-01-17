# J2CL Maven Project Archetypes

All archetypes take the usual `artifactId`, `groupId`, `version`, and `package` and also `module` to
use for naming some classes in the generated projects.

To create a project interactively, use:
```
mvn archetype:generate -DarchetypeGroupId=com.vertispan.j2cl \
-DarchetypeArtifactId=<archetype-name> \
-DarchetypeVersion=0.19-SNAPSHOT
```

To specify these four variables, add them as system properties:
```
mvn archetype:generate -DarchetypeGroupId=com.vertispan.j2cl \
-DarchetypeArtifactId=<archetype-name> \
-DarchetypeVersion=0.19-SNAPSHOT \
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
-Dartifact=com.vertispan.j2cl:<archetype-name>:0.19-SNAPSHOT
```

# `simple-project`

This project is a simple html page, with a css file, and a single Java class. It is not a good example
of how to set up a client/server project, but serves only to show how to make very simple standalone
samples.

After creating this, you'll need two consoles to start it. First, run `mvn jetty:run` in one window -
you'll know it is working when it reports "Started Jetty Server". In the other console window, run
`mvn j2cl:watch`, and wait for `Build Complete: ready for browser refresh`. With both running, open
`http://localhost:8080/` in a browser to see the app. Refresh the page in the browser after editing
any source file (and seeing the "Build Complete" message in the j2cl:watch log).

To deploy a sample to a servlet container, use `mvn verify` to build a war, then copy it from the
`target/` directory to the webapps directory of the servlet container. Alternatively, just run
`mvn jetty:run` without also running the watch command, the j2cl output will be replaced automatically
with JS compiled for production.

To reiterate, this is _not_ a suggested way to develop a real project, but is only intended for a quick
sample to get the basic idea of how the plugin functions, and to quickly experiment with J2CL and the
Closure Compiler in Maven.
