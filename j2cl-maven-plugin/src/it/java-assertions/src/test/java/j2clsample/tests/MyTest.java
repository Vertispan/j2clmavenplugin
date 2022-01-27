package j2clsample.tests;

import com.google.j2cl.junit.apt.J2clTestInput;

import org.junit.Assert;
import org.junit.Test;

/**
 * This test is a bit more complex than it perhaps needs to be, to let us verify in one single test+pom
 * that assertions are working in our various expected states. The pom should specify the assertion.armed
 * property so that this file knows whether or not to expect that the assertions are alive and will go
 * off if they fail.
 *
 * Note that this will fail in BUNDLE/BUNDLE_JAR mode since closure can't optimize out asserts.
 */
@J2clTestInput(MyTest.class)
public class MyTest {
    private static final boolean EXPECT_ASSERTIONS_ARMED = "true".equals(System.getProperty("expect.assertions.armed"));

    @Test
    public void testAssertTrue() {
        assert true : "should never fail";
    }
    @Test
    public void testAssertFalse() {
        if (EXPECT_ASSERTIONS_ARMED) {
            try {
                assert false : "should always fail";

                // We use ISE here instead of Assert.fail(), since Assert.fail() would throw an AssertionError too
                throw new IllegalStateException("Expected AssertionError when assertions were armed");
            } catch (AssertionError expected) {

            }
        } else {
            assert false : "This shouldn't go off unless assertions are armed";
        }
    }
}