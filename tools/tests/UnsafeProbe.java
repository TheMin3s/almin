public class UnsafeProbe {
  public static void main(String[] a) throws Exception {
    var f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
    f.setAccessible(true);
    Object u = f.get(null);
    var m = Class.forName("sun.misc.Unsafe").getMethod("allocateInstance", Class.class);
    Object s = m.invoke(u, Class.forName("net.minecraft.server.dedicated.DedicatedServer"));
    System.out.println("allocated: " + (s != null) + " " + s.getClass().getName());
  }
}
