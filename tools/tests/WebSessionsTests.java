import com.schecks.almin.Accounts;
import com.schecks.almin.AlminConfig;
import com.schecks.almin.WebSessions;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Staying logged in, and the two things that must still sign somebody out.
 *
 * <p>A login used to be a number in memory: every restart signed everybody out
 * and every closed browser lost the cookie. Both were consequences of where
 * sessions were kept rather than decisions about who should be logged in, so
 * they are gone. What is left has to be exactly right, because the whole
 * feature is "this credential stays good" — so the checks below are mostly
 * about the ways it stops being good: the password behind it changing, the
 * account going away, logging out, and being left alone long enough.
 */
public class WebSessionsTests {
    static int fail = 0;

    static void ck(String what, boolean ok, String saw) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (ok ? "" : "  -> " + saw));
        if (!ok) fail++;
    }

    static AlminConfig cfg;

    /**
     * SHA-256, worked out here rather than asked of the class under test.
     *
     * <p>Using {@code WebSessions.keyOf} would make "the file holds a hash and
     * not the id" true by construction: break the hashing and the check breaks
     * with it, agreeing that the file is fine.
     */
    static String sha256(String s) throws Exception {
        return java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    static void ownerPassword(String hash) throws Exception {
        Field h = AlminConfig.class.getDeclaredField("webAdminPasswordHash");
        h.setAccessible(true);
        h.set(cfg, hash);
    }

    public static void main(String[] args) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        cfg = cc.newInstance();
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, cfg);
        ownerPassword("$hash$one");

        Path dir = Files.createTempDirectory("almin-sessions");
        Path file = dir.resolve("config").resolve("almin").resolve("sessions.json");

        WebSessions s = new WebSessions();
        s.init(dir);
        String id = s.open(60, "owner");

        // Before anything reads it back: a login has to be on the disk from
        // the moment it is made, or a crash between the login and the first
        // request loses it.
        ck("a login is written down as soon as it is made", Files.isRegularFile(file), "no file");
        ck("...and is good straight away", s.valid(id), "not valid");
        ck("...and knows whose it is", "owner".equals(s.accountOf(id)),
            String.valueOf(s.accountOf(id)));

        // The whole point: the panel process going away is not a reason to
        // sign anybody out.
        WebSessions after = new WebSessions();
        after.init(dir);
        ck("a restart does not sign anybody out", after.valid(id), "the login was lost");

        // The file is a list of what to accept, so what is in it matters as
        // much as that it exists.
        String saved = Files.readString(file);
        ck("the file holds no session id anybody could paste into a cookie",
            !saved.contains(id), "the raw id is on the disk");
        ck("...it holds the hash of one instead",
            saved.contains(sha256(id)), saved);
        ck("...and no second copy of the password hash",
            !saved.contains("$hash$one"), saved);

        // The one thing the user asked to still end a login.
        ownerPassword("$hash$two");
        ck("changing the password signs that account out",
            !after.valid(id) && !s.valid(id), "the old login still worked");
        ck("...and the file forgets it too", !Files.readString(file).contains(sha256(id)),
            Files.readString(file));

        // Not from the panel, not from a command: straight into the file. The
        // check is on every request, so it does not matter which door it came
        // through or whether the server was running at the time.
        ownerPassword("$hash$three");
        WebSessions fresh = new WebSessions();
        fresh.init(dir);
        String live = fresh.open(60, "owner");
        ownerPassword("$hash$four");
        WebSessions reread = new WebSessions();
        reread.init(dir);
        ck("a password changed while the server was down ends it as well",
            !reread.valid(live), "a stale login survived a restart");

        // ---------- accounts ----------
        Accounts.init(dir);
        Accounts.Result made = Accounts.create("mod", "password12345");
        ck("an account can be made to test against", made.ok(), made.message());
        Accounts.Account mod = Accounts.byUsername("mod");
        WebSessions theirs = new WebSessions();
        theirs.init(dir);
        String modId = theirs.open(60, mod.id());
        ck("an account's login works the same way", theirs.valid(modId), "not valid");
        Accounts.setPassword(mod.id(), "different12345");
        ck("...and ends when their password changes", !theirs.valid(modId),
            "the old login still worked");

        String second = theirs.open(60, mod.id());
        ck("a new login after the change is good", theirs.valid(second), "not valid");
        Accounts.delete(mod.id());
        ck("...and deleting the account ends it", !theirs.valid(second),
            "a deleted account was still signed in");

        // ---------- the clock ----------
        ownerPassword("$hash$five");
        WebSessions clock = new WebSessions();
        clock.init(Files.createTempDirectory("almin-sessions-clock"));
        String brief = clock.open(5, "owner");
        cfg.webSessionMinutes = 5;
        // Using it must push the window out, or "logged in" would mean
        // "logged in for five minutes from the first request".
        Field f = WebSessions.class.getDeclaredField("sessions");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) f.get(clock);
        Object before = map.get(sha256(brief));
        Thread.sleep(1100);
        clock.valid(brief);
        Object moved = map.get(sha256(brief));
        ck("using a login pushes its expiry out", !before.equals(moved),
            "the window did not move");

        // And one that is genuinely left alone goes.
        WebSessions old = new WebSessions();
        Path stale = Files.createTempDirectory("almin-sessions-stale");
        Files.createDirectories(stale.resolve("config").resolve("almin"));
        Files.writeString(stale.resolve("config").resolve("almin").resolve("sessions.json"),
            "[{\"id\":\"deadbeef\",\"account\":\"owner\",\"stamp\":\"x\",\"expires\":1000}]");
        old.init(stale);
        String left = Files.readString(
            stale.resolve("config").resolve("almin").resolve("sessions.json"));
        ck("a login left alone past its window is not read back in",
            old.count() == 0 && !left.contains("deadbeef"), old.count() + " / " + left);

        // ---------- logging out ----------
        WebSessions out = new WebSessions();
        out.init(Files.createTempDirectory("almin-sessions-out"));
        String bye = out.open(60, "owner");
        out.close(bye);
        ck("logging out ends the login", !out.valid(bye), "still signed in");
        ck("...and a nonsense cookie is simply not a login",
            !out.valid("not-a-session") && out.accountOf("") == null, "something was accepted");

        // ---------- how it is wired ----------
        String web = Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
        ck("the panel reads the saved logins before it answers anything",
            web.contains("ui.sessions.init(dirOf(server));"), "nothing loads them");
        // Without this the cookie is thrown away when the browser closes, and
        // no amount of server-side memory helps.
        ck("the cookie outlives the browser window",
            web.contains("\"; Max-Age=\" + seconds"), "the cookie is still session-only");

        System.out.println(fail == 0 ? "\nWEB SESSION TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
