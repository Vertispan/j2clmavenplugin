package com.example;

import com.google.j2cl.junit.apt.J2clTestInput;

import org.junit.Assert;
import org.junit.Test;

@J2clTestInput(OptionTest.class)
public class OptionTest {
    @Test
    public void resourceContents() {
        // This test verifies that the resource contents were correctly read at build time
        MyOption res = MyOption.INSTANCE;

        Assert.assertEquals("my-option", res.getOption());
    }
}
