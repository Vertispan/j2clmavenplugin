package ${package};

import com.google.j2cl.junit.apt.J2clTestInput;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

@J2clTestInput(${module}Test.class)
public class ${module}Test {

    @Test
    public void someSimpleTest() {
        assertEquals(${module}.HELLO_WORLD, new ${module}().helloWorldString());
    }
}
