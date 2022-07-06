package example.test;

public class ValueHolder {

    public static String getExpectedValue() {
        return System.getProperty("holder.value");
    }

    public static boolean testEscape() {
        return Boolean.parseBoolean(System.getProperty("holder.testEscape"));
    }
}
