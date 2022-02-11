package com.example;

@MyAnnotation
public interface MyResources {
    public static final MyResources INSTANCE = new MyResources_Impl();

    @MyAnnotation("res-in-root-dir.txt")
    String resourceInRoot();
    @MyAnnotation("com/example/res-in-package.txt")
    String resourceInPackage();

    @MyAnnotation("res-in-java-default-package.txt")
    String resourceInJavaSourceRoot();

    @MyAnnotation("com/example/res-in-java-nested-package.txt")
    String resourceInJavaPackage();
}
