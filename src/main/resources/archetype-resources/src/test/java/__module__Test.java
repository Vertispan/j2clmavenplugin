package ${package};

import com.google.j2cl.junit.apt.J2clTestInput;
import junit.framework.TestCase;

/**
 * @author Dmitrii Tikhomirov
 * Created by treblereel 2/11/20
 */
@J2clTestInput(${module}Test.class)
public class ${module}Test extends TestCase {

    public void testGetRanges() {
        assertEquals(${module}.HELLO_WORLD, new ${module}().helloWorldString());
    }
}