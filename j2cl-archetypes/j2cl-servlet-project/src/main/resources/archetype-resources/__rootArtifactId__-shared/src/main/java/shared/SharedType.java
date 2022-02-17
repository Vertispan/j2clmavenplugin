package ${package}.shared;

/**
 * Represents some type that could be shared between client and server.
 */
public class SharedType {
    public static String sayHello(String name) {
        return "Hello, " + name + "!";
    }
}