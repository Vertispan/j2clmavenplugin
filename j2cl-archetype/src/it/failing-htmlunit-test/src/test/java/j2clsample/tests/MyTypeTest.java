package j2clsample.tests;

import com.google.j2cl.junit.apt.J2clTestInput;

import j2clsample.nontest.MyType;

import org.junit.Assert;
import org.junit.Test;

@J2clTestInput(MyTypeTest.class)
public class MyTypeTest {
    @Test
    public void testTheMethod() {
        Assert.assertEquals("foo", new MyType().testMe());
    }
}