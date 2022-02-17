package ${package}.shared;

import org.junit.Assert;
import org.junit.Test;
import com.google.j2cl.junit.apt.J2clTestInput;


/**
 * Represents some type that could be shared between client and server.
 */
@J2clTestInput(SharedTypeTest.class)
public class SharedTypeTest {
    @Test
    public void sayHello() {
        Assert.assertEquals("Hello, Foo!", SharedType.sayHello("Foo"));
    }
}