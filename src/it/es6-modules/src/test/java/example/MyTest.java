package example;

import com.google.j2cl.junit.apt.J2clTestInput;

import user.defined.externs.SampleJsModule;

import org.junit.Assert;
import org.junit.Test;

@J2clTestInput(MyTest.class)
public class MyTest {
    @Test
    public void testText() {
        Assert.assertEquals("Hello from a module", SampleJsModule.helloWorld());
    }
}