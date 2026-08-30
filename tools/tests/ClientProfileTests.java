import com.schecks.almin.ClientProfilePayload;
import com.schecks.almin.ClientProfiles;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * What each client is running, and what changed.
 *
 * The whole value of this is the second half — a list that only shows the
 * present cannot answer "what changed since it worked" — so most of what is
 * checked here is the merge: what counts as new, what counts as removed, and
 * what a mod coming back does to a removal.
 */
public class ClientProfileTests {
    static int failures = 0;
    static final UUID WHO = UUID.fromString("11111111-2222-3333-4444-555555555555");

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("almin-clients");
        Field f = ClientProfiles.class.getDeclaredField("file");
        f.setAccessible(true);
        f.set(null, dir.resolve("clients.json"));
        setDays(7);

        // ---- first sighting ----
        List<String> added = ClientProfiles.record(WHO, "Steve",
            say("almin@2.22.0", "fabric-api@0.116.0", "lithium@0.14.3"));
        check("the first sighting is not forty new mods", added.isEmpty());
        ClientProfiles.Profile p = ClientProfiles.of(WHO);
        check("everything is present", p.present().size() == 3);
        check("nothing has been removed", p.removed().isEmpty());
        check("present is alphabetical",
            p.present().get(0).id().equals("almin")
            && p.present().get(1).id().equals("fabric-api")
            && p.present().get(2).id().equals("lithium"));
        check("the machine is recorded", p.os().equals("Mac OS X") && p.cores() == 10);

        // ---- something added, something taken away ----
        Thread.sleep(5);
        added = ClientProfiles.record(WHO, "Steve",
            say("almin@2.22.0", "fabric-api@0.116.0", "sodium@0.6.13"));
        check("the new one is reported", added.equals(List.of("sodium")));
        p = ClientProfiles.of(WHO);
        check("it joins the present list", p.present().size() == 3
            && p.present().get(2).id().equals("sodium"));
        check("the missing one is marked removed", p.removed().size() == 1
            && p.removed().get(0).id().equals("lithium"));
        check("a removal carries when it went", p.removed().get(0).removedAt() > 0);

        // ---- the one that was always there keeps its date ----
        long since = firstSeen(p, "almin");
        Thread.sleep(5);
        ClientProfiles.record(WHO, "Steve", say("almin@2.22.0", "sodium@0.6.13"));
        check("a mod that stayed keeps the date it arrived",
            firstSeen(ClientProfiles.of(WHO), "almin") == since);

        // ---- coming back clears the removal ----
        ClientProfiles.record(WHO, "Steve",
            say("almin@2.22.0", "sodium@0.6.13", "lithium@0.14.3"));
        p = ClientProfiles.of(WHO);
        check("a mod that comes back is not a ghost",
            p.removed().stream().noneMatch(m -> m.id().equals("lithium")));
        check("...and is present again",
            p.present().stream().anyMatch(m -> m.id().equals("lithium")));

        // ---- a version change is not an add ----
        added = ClientProfiles.record(WHO, "Steve",
            say("almin@2.23.0", "sodium@0.6.13", "lithium@0.14.3"));
        check("upgrading a mod is not installing one", added.isEmpty());
        check("...and the new version is what is shown",
            version(ClientProfiles.of(WHO), "almin").equals("2.23.0"));

        // ---- removals expire ----
        ClientProfiles.record(WHO, "Steve", say("almin@2.23.0"));
        // fabric-api went two joins ago, sodium and lithium just now.
        int gone = ClientProfiles.of(WHO).removed().size();
        check("everything that went is listed as gone (" + gone + ")", gone == 3);
        setDays(1);
        agedRemovals(WHO, System.currentTimeMillis() - 3 * 86_400_000L);
        check("a removal older than the window is forgotten",
            ClientProfiles.of(WHO).removed().isEmpty());
        check("...but the present list is untouched",
            ClientProfiles.of(WHO).present().size() == 1);

        // ---- restricted ----
        setRestricted("xaerominimap, LITEMATICA ");
        ClientProfiles.record(WHO, "Steve",
            say("almin@2.23.0", "xaerominimap@24.2", "sodium@0.6"));
        List<String> hits = ClientProfiles.restricted(ClientProfiles.of(WHO));
        check("a restricted mod is spotted", hits.equals(List.of("xaerominimap")));
        check("the list is matched case-insensitively",
            ClientProfiles.restrictedSet().contains("litematica"));
        setRestricted("");
        check("nothing restricted means nothing flagged",
            ClientProfiles.restricted(ClientProfiles.of(WHO)).isEmpty());

        // ---- it survives a restart ----
        Method load = ClientProfiles.class.getDeclaredMethod("load");
        load.setAccessible(true);
        Field profiles = ClientProfiles.class.getDeclaredField("profiles");
        profiles.setAccessible(true);
        ((Map<?, ?>) profiles.get(null)).clear();
        load.invoke(null);
        check("what was written comes back", ClientProfiles.of(WHO) != null
            && ClientProfiles.of(WHO).present().size() == 3);

        // ---- the payload will not carry an essay ----
        check("a long field is clipped rather than refused",
            ClientProfilePayload.clip("x".repeat(500)).length()
                == ClientProfilePayload.MAX_FIELD);
        check("a newline cannot get into a field",
            !ClientProfilePayload.clip("a\nb").contains("\n"));

        // ---- what is bundled inside what ----
        // Its own client, so the counting story above is left alone.
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        ClientProfiles.record(other, "Alex", say("almin@2.22.0", "sodium@0.6.13",
            "fabric-networking-api-v1@4.4.2^fabric-api", "fabric-api@0.116.0"));
        ClientProfiles.Profile bundle = ClientProfiles.of(other);
        ClientProfiles.Mod nested = pick(bundle, "fabric-networking-api-v1");
        ClientProfiles.Mod chosen = pick(bundle, "sodium");
        check("a bundled mod carries what ships it",
            nested != null && nested.parent().equals("fabric-api") && !nested.own());
        check("...and its version is still read off the same entry",
            nested != null && nested.version().equals("4.4.2"));
        check("something the player installed carries no parent",
            chosen != null && chosen.own());

        // A client that predates the field sends no caret. That must read as
        // "installed", which is the safe way round: hiding a mod nobody
        // bundled would be worse than showing one that was.
        Thread.sleep(5);
        ClientProfiles.record(other, "Alex", say("almin@2.22.0", "sodium@0.6.13",
            "fabric-networking-api-v1@4.4.2", "fabric-api@0.116.0"));
        check("an older client that says nothing is not guessed at",
            pick(ClientProfiles.of(other), "fabric-networking-api-v1").own());

        // Forty bundled modules arriving at once is not forty installs.
        Thread.sleep(5);
        List<String> bundled = ClientProfiles.record(other, "Alex",
            say("almin@2.22.0", "sodium@0.6.13", "fabric-networking-api-v1@4.4.2",
                "fabric-api@0.116.0", "fabric-rendering-v1@6.0.1^fabric-api",
                "fabric-item-api-v1@11.2.0^fabric-api"));
        check("bundled modules are not reported as things somebody installed",
            bundled.isEmpty());


        System.out.println(failures == 0 ? "CLIENTS OK" : "CLIENT FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }

    static ClientProfilePayload say(String... mods) {
        return new ClientProfilePayload("1.21.9", "fabric 0.19.4", "minecraft-launcher 2.3",
            "Mac OS X", "15.3.1", "aarch64", "21.0.5", 10, 4096, List.of(mods));
    }

    static long firstSeen(ClientProfiles.Profile p, String id) {
        for (ClientProfiles.Mod m : p.mods()) if (m.id().equals(id)) return m.firstSeen();
        return -1;
    }

    static String version(ClientProfiles.Profile p, String id) {
        for (ClientProfiles.Mod m : p.present()) if (m.id().equals(id)) return m.version();
        return "";
    }

    /** Backdates every removal, so expiry can be tested without waiting a week. */
    @SuppressWarnings("unchecked")
    static void agedRemovals(UUID id, long when) throws Exception {
        Field pf = ClientProfiles.class.getDeclaredField("profiles");
        pf.setAccessible(true);
        Map<UUID, ClientProfiles.Profile> map =
            (Map<UUID, ClientProfiles.Profile>) pf.get(null);
        ClientProfiles.Profile p = map.get(id);
        List<ClientProfiles.Mod> mods = new ArrayList<>();
        for (ClientProfiles.Mod m : p.mods()) {
            mods.add(m.gone()
                ? new ClientProfiles.Mod(m.id(), m.version(), m.firstSeen(), when)
                : m);
        }
        map.put(id, new ClientProfiles.Profile(p.uuid(), p.name(), p.at(), p.minecraft(),
            p.loader(), p.launcher(), p.os(), p.osVersion(), p.arch(), p.java(),
            p.cores(), p.memoryMb(), List.copyOf(mods)));
    }

    static ClientProfiles.Mod pick(ClientProfiles.Profile p, String id) {
        for (ClientProfiles.Mod m : p.mods()) if (m.id().equals(id)) return m;
        return null;
    }

    static void setDays(int days) throws Exception {
        Class<?> cfg = Class.forName("com.schecks.almin.AlminConfig");
        Object c = cfg.getMethod("get").invoke(null);
        cfg.getField("clientModHistoryDays").setInt(c, days);
        cfg.getField("clientReport").setBoolean(c, true);
        Field inst = cfg.getDeclaredField("instance"); inst.setAccessible(true);
        inst.set(null, c);
    }

    static void setRestricted(String value) throws Exception {
        Class<?> cfg = Class.forName("com.schecks.almin.AlminConfig");
        Object c = cfg.getMethod("get").invoke(null);
        cfg.getField("modsRestricted").set(c, value);
        Field inst = cfg.getDeclaredField("instance"); inst.setAccessible(true);
        inst.set(null, c);
    }
}
