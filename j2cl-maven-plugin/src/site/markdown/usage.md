To use this plugin, first consider which aspects of your project can be compiled to JavaScript. Unlike the Mojo plugin
for GWT, this plugin discourages mixing server-only and client-only code in the same project. Instead, there should be
server-specific and client-specific projects, though they made share some common "shared" project dependencies to avoid
code duplication. Any shared code and its dependencies should build and run cleanly on both platforms to avoid later
headaches.

It is further encouraged that no client project get "too big", but that wherever reasonable, the client code be split
into discrete modules. This helps not only encourage developers to put code where it belongs instead of one giant source
directory full of circular dependencies, but will help the j2cl-maven-plugin parallelize builds when code changes -
especially important for "dev mode".

Finally it is assumed that you might use any server you want, and this plugin tries to avoid making any assumptions
about what might be used. The only assumption made is this: you already know how to start your server for development, 
and that there is a way to write static content to disk somewhere and let it be served to your browser.

Once the project is organized in this way, the j2cl-maven-plugin should be added to any client-only project that will
have tests or produce runnable JavaScript:

```
    <build>
        <plugins>
            <plugin>
                <groupId>com.vertispan.j2cl</groupId>
                <artifactId>j2cl-maven-plugin</artifactId>
                <version>${project.version}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>build</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
```

This will default to producing optimized JavaScript of the application in the `prepare-package` phase. See the 
[configuration options for j2cl:build](build-mojo.html) for more details on how to customize this.

By default, for a project named `my-app` with version `1.2.3-SNAPSHOT` output will be written to the
`target/my-app-1.2.3-SNAPSHOT` directory, under the assumption that a war file will be built around this content, and
used as an overlay for the server war. This directory can be customized using 
[`<webappDirectory>`](build-mojo.html#webappDirectory). The actual output JS file in turn would be in that directory, 
in `my-app/my-app.js` - other generated resources would live in the same directory. To avoid this extra wrapping
directory or to change the name of the output JS, the [`<initialScriptFilename>`](build-mojo.html#initialScriptFilename)
configuration property can be set. 

To run tests, add an execution for the `test` goal. The configuration settings above are named the same for `test` and
`build`, so if they are customized, each should be declared in its own execution:
```
                <executions>
                    <execution>
                        <id>test-js</id>
                        <goals>
                            <goal>test</goal>
                        </goals>
                        <configuration>
                            <!-- htmlunit requires output transpiled down to remove es2015 class -->
                            <compilationLevel>ADVANCED</compilationLevel>
                        </configuration>
                    </execution>
                </executions>

```

For a multi-module client project (and for reasons discussed above, most projects will be multi-module if there is any
code shared), the development mode feature should be configured in the parent pom, and invoked from there. This goal is
called `j2cl:watch`, and while this will not run a server, it will watch for any changes in the child projects from
where it is run, and build to the directory of your choosing. Note that 
[`<webappDirectory>`](watch-mojo.html#webappDirectory) must be specified if run on a parent pom - this should be
configured to tell the plugin where to write generated output such that your web server can see it, serve it to the
browser. The `watch` goal will examine each child project and collect `j2cl:build` executions, and build all of them 
into that one directory.

So that the `<webappDirectory>` for `j2cl:watch` does not become the default for all child projects, be sure to set
`<inherited>false</inherited>`.

```
            <plugin>
                <groupId>com.vertispan.j2cl</groupId>
                <artifactId>j2cl-maven-plugin</artifactId>
                <inherited>false</inherited>
                <configuration>
                    <webappDirectory>${project.build.directory}/j2cl-watch</webappDirectory>
                </configuration>
            </plugin>
```

Of course, if you run `j2cl:watch` in the client project itself and don't take advantage of rebuilding other reactor
projects as they are changed, additional configuration is unnecessary - but if a different webapp directory is desired, 
the system property `j2cl.webappDirectory` can be set (such as `mvn j2cl:watch -Dj2cl.webappDirectory=path/to/server`).
