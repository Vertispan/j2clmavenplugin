package com.example;

public class MyApp {
    public static void start() {
        MyResources.INSTANCE.resourceInRoot();
        try {
            BasicParser.parse("{{}}");
        } catch(Exception ex) {
            // parse exception ignored
        }
    }
}
