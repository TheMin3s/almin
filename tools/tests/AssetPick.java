import com.schecks.almin.UpdateChecker;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Serves a fake GitHub release payload and checks each side picks its own jar. */
public class AssetPick {
    static int fail=0;
    static void ck(String w, boolean ok, String d){ System.out.println((ok?"  PASS  ":"  FAIL  ")+w+(ok?"":"  -> "+d)); if(!ok) fail++; }

    static String body(String assets){
        return "{\"tag_name\":\"v2.0.0\",\"assets\":[" + assets + "]}";
    }
    static String asset(String name){
        return "{\"name\":\""+name+"\",\"browser_download_url\":\"https://example.invalid/"+name+"\"}";
    }

    public static void main(String[] a) throws Exception {
        // Two-jar release, server listed first.
        String twoJars = body(asset("almin-2.0.0-server.jar")+","+asset("almin-2.0.0-client.jar"));
        // Two-jar release, client listed first (upload order must not matter).
        String reversed = body(asset("almin-2.0.0-client.jar")+","+asset("almin-2.0.0-server.jar"));
        // Legacy single-jar release, from before the split.
        String legacy   = body(asset("almin-1.18.9.jar"));

        ck("server picks server jar (server first)", pick(twoJars,"server").endsWith("-server.jar"), pick(twoJars,"server"));
        ck("client picks client jar (server first)", pick(twoJars,"client").endsWith("-client.jar"), pick(twoJars,"client"));
        ck("server picks server jar (client first)", pick(reversed,"server").endsWith("-server.jar"), pick(reversed,"server"));
        ck("client picks client jar (client first)", pick(reversed,"client").endsWith("-client.jar"), pick(reversed,"client"));
        ck("legacy single jar still resolves for server", pick(legacy,"server").equals("almin-1.18.9.jar"), pick(legacy,"server"));
        ck("legacy single jar still resolves for client", pick(legacy,"client").equals("almin-1.18.9.jar"), pick(legacy,"client"));

        System.out.println(fail==0?"\nASSET-PICK TESTS PASSED":"\n"+fail+" FAILED");
        System.exit(fail==0?0:1);
    }

    /** Spins a one-shot server returning `json`, and asks UpdateChecker to parse it. */
    static String pick(String json, String want) throws Exception {
        HttpServer h = HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        h.createContext("/", ex -> {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type","application/json");
            ex.sendResponseHeaders(200,b.length);
            ex.getResponseBody().write(b); ex.close();
        });
        h.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
        h.start();
        try {
            Method m = UpdateChecker.class.getDeclaredMethod("fetchReleaseFrom", java.net.URI.class, String.class);
            m.setAccessible(true);
            Object rel = m.invoke(null, java.net.URI.create("http://127.0.0.1:"+h.getAddress().getPort()+"/"), want);
            Method jarName = rel.getClass().getDeclaredMethod("jarName");
            return String.valueOf(jarName.invoke(rel));
        } finally { h.stop(0); }
    }
}
