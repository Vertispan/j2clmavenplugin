package ${package}.shared;

import org.junit.Assert;
import org.junit.Test;

/**
 * Represents some type that could be shared between client and server.
 */
public class SharedTypeTest {
    @Test
    public void sayHello() {
        Assert.assertEquals("Hello, Foo!", SharedType.sayHello("Foo"));
    }
}