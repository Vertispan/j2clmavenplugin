J2CL Maven plugin
=================

Thius plugin works using the

        com.vertispan.j2cl:build-tools

built from here:

    https://github.com/gitgabrio/j2cl-devmode-strawman

------------------------
The plugin has two goals

1 build: it starts compilation

2 clean: it cleans up all the plugin-specific directories

----------------------
To test it:

1 clone https://github.com/gitgabrio/connected

2 switch to branch j2cl-mavenplugin

3 issue mvn package -Pdevmode

The connected project has been modified so that

1 all the dependencies and compiled classes/js ends up inside target/webapp

2 the jetty server listen for modification and serves target/webapp



