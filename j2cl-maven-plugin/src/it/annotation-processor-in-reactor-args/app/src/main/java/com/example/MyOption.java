package com.example;

@MyAnnotation
public interface MyOption {
    MyOption INSTANCE = new MyOption_Impl();

    String getOption();

}
