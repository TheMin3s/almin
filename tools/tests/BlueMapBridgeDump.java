import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the separately served BlueMap bridge so Node can syntax-check it. */
public class BlueMapBridgeDump {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.schecks.almin.BlueMapIntegration");
        Field field = type.getDeclaredField("BRIDGE");
        field.setAccessible(true);
        String bridge = (String) field.get(null);
        Files.writeString(Path.of(args[0]), bridge);
        System.out.println("bridge " + bridge.length() + " chars");
    }
}
