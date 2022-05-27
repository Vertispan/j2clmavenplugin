package com.example;

@MyAnnotation
public interface MyTestResources {
    public static final MyTestResources INSTANCE = new MyTestResources_Impl();

    @MyAnnotation("test-res-in-root-dir.txt")
    String testResourceInRoot();

}
