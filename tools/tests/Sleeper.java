/** A java process that does nothing but exist, so a kill can be observed. */
public class Sleeper {
    public static void main(String[] a) throws Exception {
        Thread.sleep(600_000);
    }
}
