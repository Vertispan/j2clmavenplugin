package com.example;

import com.google.j2cl.junit.apt.J2clTestInput;

import org.junit.Assert;
import org.junit.Test;

@J2clTestInput(ResourceTest.class)
public class ResourceTest {
    @Test
    public void resourceContents() {
        // This test verifies that the resource contents were correctly read at build time
        MyResources res = MyResources.INSTANCE;

        Assert.assertEquals("res-in-root-dir.txt", res.resourceInRoot());
        Assert.assertEquals("res-in-package.txt", res.resourceInPackage());
        Assert.assertEquals("res-in-java-default-package.txt", res.resourceInJavaSourceRoot());
        Assert.assertEquals("res-in-java-nested-package.txt", res.resourceInJavaPackage());

        MyTestResources testRes = MyTestResources.INSTANCE;
        Assert.assertEquals("test-res-in-root-dir.txt", testRes.testResourceInRoot());
    }
}
