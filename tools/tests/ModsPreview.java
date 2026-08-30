import com.schecks.almin.*;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.file.*;

/** Serves the real panel with a couple of advertised mods, for a visual check. */
public class ModsPreview {
    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc=AlminConfig.class.getDeclaredConstructor(); cc.setAccessible(true);
        AlminConfig cfg=cc.newInstance();
        set(AlminConfig.class,"webAdminPasswordHash",cfg,Passwords.hash("demo12345"));
        setBool(AlminConfig.class,"modsDenyKicks",cfg,true);
        setBool(AlminConfig.class,"requireClientMod",cfg,true);
        Field inst=AlminConfig.class.getDeclaredField("instance"); inst.setAccessible(true); inst.set(null,cfg);

        Path dir=Files.createTempDirectory("alminmods");
        Path mfd=dir.resolve("modfiles"); Files.createDirectories(mfd);
        // a minimal but real Fabric-looking jar so listing/validation behave
        try(var zos=new java.util.zip.ZipOutputStream(Files.newOutputStream(mfd.resolve("sodium-0.5.11.jar")))){
            zos.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
            zos.write("{\"id\":\"sodium\"}".getBytes()); zos.closeEntry();
        }
        Field pathF=ModOffers.class.getDeclaredField("path"); pathF.setAccessible(true);
        pathF.set(null, dir.resolve("mods.json"));
        Field mfF=ModOffers.class.getDeclaredField("modFilesDir"); mfF.setAccessible(true);
        mfF.set(null, mfd);
        ModOffers.add(new ModOffers.AdvertisedMod("sodium","Sodium","0.5.11",
            "",
            "9f2b1c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff0",true,"sodium-0.5.11.jar"));
        ModOffers.add(new ModOffers.AdvertisedMod("lithium","Lithium","0.12.1",
            "https://cdn.modrinth.com/data/lithium-0.12.1.jar","",false,""));

        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",8793),16);
        Constructor<WebUi> wc=WebUi.class.getDeclaredConstructor(
            HttpServer.class, java.util.concurrent.ExecutorService.class,
            net.minecraft.server.MinecraftServer.class, String.class, int.class);
        wc.setAccessible(true);
        WebUi ui=wc.newInstance(http,null,"127.0.0.1",8793);
        set(WebUi.class,"publicJson",ui,"{\"rows\":[],\"generated\":1}");
        set(WebUi.class,"fullJson",ui,"{\"rows\":[],\"generated\":1}");
        for(String[] r: new String[][]{{"/","handleRoot"},{"/api/session","handleSession"},
                {"/api/public","handlePublic"},{"/api/login","handleLogin"},{"/api/state","handleState"},
                {"/api/mods","handleMods"},{"/api/mods/save","handleModSave"},{"/api/mods/delete","handleModDelete"},
                {"/api/mods/files","handleModFiles"}}){
            Method m=WebUi.class.getDeclaredMethod(r[1], com.sun.net.httpserver.HttpExchange.class);
            m.setAccessible(true);
            http.createContext(r[0], ex->{ try{m.invoke(ui,ex);}catch(Exception e){throw new RuntimeException(e);} });
        }
        http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        http.start();
        System.out.println("mods preview on http://127.0.0.1:8793/");
        Thread.currentThread().join();
    }
    static void set(Class<?> c,String f,Object o,String v)throws Exception{Field x=c.getDeclaredField(f);x.setAccessible(true);x.set(o,v);}
    static void setBool(Class<?> c,String f,Object o,boolean v)throws Exception{Field x=c.getDeclaredField(f);x.setAccessible(true);x.setBoolean(o,v);}
}
