import com.schecks.almin.Accounts;
import com.schecks.almin.Passwords;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Who may sign in, and what they may reach.
 *
 * The rules here are the ones that matter if they are wrong: an account that
 * can see something it was never granted, a new account that starts with
 * anything, or an owner that another account can reach.
 */
public class AccountTests {
    static int failures = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("almin-accounts");
        configureOwner("admin", Passwords.hash("owner-password"));
        Accounts.init(dir);

        // ---- making one ----
        check("an empty list to begin with", Accounts.all().isEmpty());

        Accounts.Result made = Accounts.create("moderator", "hunter2hunter2");
        check("an account can be made: " + made.message(), made.ok());
        check("...and is listed", Accounts.all().size() == 1);

        check("a duplicate name is refused",
            !Accounts.create("MODERATOR", "another-password").ok());
        check("a short password is refused",
            !Accounts.create("second", "short").ok());
        check("a name with a space is refused",
            !Accounts.create("two words", "long-enough-password").ok());

        Accounts.Account mod = Accounts.byUsername("moderator");
        check("the name is found case-insensitively", mod != null);

        // ---- the default is nothing ----
        boolean blank = true;
        for (String menu : Accounts.MENUS) blank &= mod.level(menu).equals(Accounts.NONE);
        check("a new account may reach nothing at all", blank);
        check("...so it cannot read", !mod.canRead("files"));
        check("...and cannot write", !mod.canWrite("files"));

        // ---- grants ----
        Accounts.setAccess(mod.id(), "activity", Accounts.READ);
        Accounts.setAccess(mod.id(), "players", Accounts.WRITE);
        mod = Accounts.byUsername("moderator");
        check("read means read", mod.canRead("activity") && !mod.canWrite("activity"));
        check("write means both", mod.canRead("players") && mod.canWrite("players"));
        check("an ungranted menu stays shut", !mod.canRead("term"));
        check("a menu that does not exist is refused",
            !Accounts.setAccess(mod.id(), "nonsense", Accounts.WRITE).ok());

        Accounts.setAccess(mod.id(), "players", "none");
        check("a grant can be taken away",
            !Accounts.byUsername("moderator").canRead("players"));

        // ---- the owner ----
        Accounts.Account own = Accounts.owner();
        check("the owner holds every menu",
            Accounts.MENUS.stream().allMatch(own::canWrite));
        check("the owner is marked as such", own.owner());
        check("the owner is not in the list", Accounts.all().stream().noneMatch(x -> x.owner()));
        check("the owner cannot be edited through the list",
            !Accounts.setAccess("owner", "files", Accounts.NONE).ok());
        check("...nor deleted", !Accounts.delete("owner").ok());
        check("...nor have their password set here", !Accounts.setPassword("owner", "x".repeat(12)).ok());
        check("the owner still holds everything afterwards",
            Accounts.MENUS.stream().allMatch(m -> Accounts.owner().canWrite(m)));

        // ---- signing in ----
        check("the owner is found by their configured name",
            Accounts.byUsername("admin") != null && Accounts.byUsername("admin").owner());
        check("the owner's password verifies",
            Passwords.verify("owner-password", Accounts.byUsername("admin").hash()));
        check("a made account's password verifies",
            Passwords.verify("hunter2hunter2", Accounts.byUsername("moderator").hash()));
        check("the wrong password does not",
            !Passwords.verify("hunter2hunter3", Accounts.byUsername("moderator").hash()));

        // ---- linking to a player ----
        check("a linked player is remembered",
            Accounts.link(mod.id(), "Steve", "00000000-0000-0000-0000-0000000000aa").ok()
                && "Steve".equals(Accounts.byUsername("moderator").mcName()));
        check("a name that is not a Minecraft name is refused",
            !Accounts.link(mod.id(), "not a name!", "").ok());
        check("unlinking clears the uuid as well",
            Accounts.link(mod.id(), "", "").ok()
                && Accounts.byUsername("moderator").mcUuid().isEmpty());

        // ---- watching them ----
        check("recording is off to begin with", !Accounts.byUsername("moderator").auditActivity());
        Accounts.setAudit(mod.id(), true);
        check("...and can be turned on", Accounts.byUsername("moderator").auditActivity());

        // ---- it survives a restart ----
        Accounts.setAccess(mod.id(), "files", Accounts.READ);
        Accounts.init(dir);
        Accounts.Account back = Accounts.byUsername("moderator");
        check("the list is read back from disk", back != null);
        check("...with its grants", back.canRead("files") && !back.canWrite("files"));
        check("...and its recording setting", back.auditActivity());
        check("...and its password still verifies",
            Passwords.verify("hunter2hunter2", back.hash()));

        // ---- what is on disk ----
        String raw = Files.readString(dir.resolve("config").resolve("almin")
            .resolve(Accounts.fileName()));
        check("the password is not on disk in the clear", !raw.contains("hunter2hunter2"));
        check("the hash is", raw.contains("pbkdf2_sha256"));

        // ---- renaming and removing ----
        check("renaming keeps the grants",
            Accounts.rename(back.id(), "helper").ok()
                && Accounts.byUsername("helper").canRead("files"));
        check("the old name is gone", Accounts.byUsername("moderator") == null);
        check("a rename onto the owner's name is refused",
            !Accounts.rename(back.id(), "admin").ok());
        check("removing works", Accounts.delete(back.id()).ok() && Accounts.all().isEmpty());
        check("removing something that is not there is refused",
            !Accounts.delete(back.id()).ok());

        System.out.println(failures == 0 ? "ACCOUNTS OK" : failures + " ACCOUNT FAILURES");
        if (failures > 0) System.exit(1);
    }

    /** Points the config's owner account at a known name and password. */
    static void configureOwner(String username, String hash) throws Exception {
        Class<?> cfg = Class.forName("com.schecks.almin.AlminConfig");
        Object c = cfg.getMethod("get").invoke(null);
        cfg.getField("webAdminUsername").set(c, username);
        cfg.getField("webAdminPasswordHash").set(c, hash);
        Field inst = cfg.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, c);
    }

    static List<String> menus() { return Accounts.MENUS; }
}
