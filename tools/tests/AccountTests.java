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

        // ---- which folders they may reach ----
        // This is a security rule, not a convenience: dir-writable-roots
        // includes config, and Almin's own settings are a file in there, so
        // "may edit files" was "may set the owner's password" until the tree
        // could be narrowed.
        Accounts.create("filer", "filer-password-1");
        Accounts.Account fr = Accounts.byUsername("filer");
        Accounts.setAccess(fr.id(), "files", Accounts.WRITE);
        fr = Accounts.byUsername("filer");

        check("an account nobody narrowed reaches everything",
            fr.canSeePath("config/almin/config.json") && fr.canWritePath("mods/x.jar"));
        check("...and is not marked as narrowed", !fr.folderLimited());

        Accounts.setFolder(fr.id(), "mods", Accounts.WRITE);
        fr = Accounts.byUsername("filer");
        check("naming one folder shuts the rest", !fr.canSeePath("config/almin/config.json"));
        check("...including for writes", !fr.canWritePath("config/almin/config.json"));
        check("the named folder is reachable", fr.canSeePath("mods/sodium.jar"));
        check("...and writable", fr.canWritePath("mods/sodium.jar"));
        check("...at any depth", fr.canWritePath("mods/nested/deep/thing.json"));
        check("the account is marked as narrowed", fr.folderLimited());

        Accounts.setFolder(fr.id(), "logs", Accounts.READ);
        fr = Accounts.byUsername("filer");
        check("a read folder can be seen", fr.canSeePath("logs/latest.log"));
        check("...and not written", !fr.canWritePath("logs/latest.log"));

        check("the root itself stays listable", fr.canSeePath(""));
        check("...but is never writable, so nothing lands beside the folders",
            !fr.canWritePath(""));
        check("a backslash cannot smuggle a path past the top-level check",
            !fr.canSeePath("config\\almin\\config.json"));
        check("nor a leading slash", !fr.canSeePath("/config/almin/config.json"));
        check("a folder name with a slash in it is refused",
            !Accounts.setFolder(fr.id(), "config/almin", Accounts.WRITE).ok());
        check("so is one that climbs", !Accounts.setFolder(fr.id(), "..", Accounts.WRITE).ok());

        Accounts.clearFolders(fr.id());
        fr = Accounts.byUsername("filer");
        check("the whole tree can be handed back",
            fr.canWritePath("config/almin/config.json") && !fr.folderLimited());

        Accounts.setFolder(fr.id(), "mods", Accounts.WRITE);
        Accounts.init(dir);
        check("the narrowing survives a restart",
            !Accounts.byUsername("filer").canSeePath("config/x"));
        check("the owner is never narrowed",
            Accounts.owner().canWritePath("config/almin/config.json"));
        Accounts.delete(Accounts.byUsername("filer").id());

        // Every file route asks before it acts.
        String webSrc = Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
        int guards = webSrc.split("allowedPath\\(ex", -1).length - 1;
        check("every file route checks the folder first (" + guards + " calls)", guards >= 8);

        // ---- what a watched account did ----
        // Two rules, and both fail dangerously if they are wrong: nothing is
        // written for somebody who was not switched on, and the owner is never
        // written at all.
        com.schecks.almin.PanelAudit.init(dir);
        Accounts.create("watched", "watched-password-1");
        Accounts.create("unwatched", "unwatched-password");
        Accounts.setAudit(Accounts.byUsername("watched").id(), true);
        Accounts.Account seen = Accounts.byUsername("watched");
        Accounts.Account unseen = Accounts.byUsername("unwatched");

        com.schecks.almin.PanelAudit.note(unseen, "read the activity log", "");
        check("an account nobody asked to watch is not recorded",
            com.schecks.almin.PanelAudit.forUser("unwatched").isEmpty());

        com.schecks.almin.PanelAudit.note(Accounts.owner(), "read the activity log", "");
        check("the owner is never recorded",
            com.schecks.almin.PanelAudit.forUser("admin").isEmpty());

        com.schecks.almin.PanelAudit.note(seen, "read the activity log", "player=Steve");
        check("a watched account is recorded",
            com.schecks.almin.PanelAudit.forUser("watched").size() == 1);
        check("...with what it was about",
            com.schecks.almin.PanelAudit.forUser("watched").get(0).detail().equals("player=Steve"));

        com.schecks.almin.PanelAudit.note(seen, "read the activity log", "player=Steve");
        com.schecks.almin.PanelAudit.note(seen, "read the activity log", "player=Steve");
        java.util.List<com.schecks.almin.PanelAudit.Entry> folded =
            com.schecks.almin.PanelAudit.forUser("watched");
        check("polling the same view folds into one entry", folded.size() == 1);
        check("...that counts them", folded.get(0).count() == 3);

        com.schecks.almin.PanelAudit.note(seen, "read the activity log", "player=Alex");
        check("a different thing is its own entry",
            com.schecks.almin.PanelAudit.forUser("watched").size() == 2);
        check("the newest is first",
            com.schecks.almin.PanelAudit.forUser("watched").get(0).detail().equals("player=Alex"));

        check("routes are described in words a person reads",
            com.schecks.almin.PanelAudit.describe("/api/insights").contains("model")
                && com.schecks.almin.PanelAudit.describe("/api/reset").contains("cleared"));

        com.schecks.almin.PanelAudit.flush();
        com.schecks.almin.PanelAudit.init(dir);
        check("the record survives a restart",
            com.schecks.almin.PanelAudit.forUser("watched").size() == 2);

        com.schecks.almin.PanelAudit.forget("watched");
        check("removing the account takes its record with it",
            com.schecks.almin.PanelAudit.forUser("watched").isEmpty());

        // ---- every route is classified ----
        // The check that matters over time: a route added later and not put
        // in ROUTE_MENU would be reachable by every account regardless of
        // what they were granted. This reads the source rather than the
        // running server, because that is where the omission would be.
        String web = Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
        java.util.List<String> open = List.of("/", "/api/session", "/api/public", "/api/login",
            "/api/logout", "/api/accounts", "/api/head");
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("createContext\\(\"([^\"]+)\"").matcher(web);
        String table = web.substring(web.indexOf("ROUTE_MENU = "),
            web.indexOf("/** Whether this method is asking to change something. */"));
        java.util.List<String> unclassified = new java.util.ArrayList<>();
        int routes = 0;
        while (m.find()) {
            String path = m.group(1);
            routes++;
            if (open.contains(path)) continue;
            if (!table.contains("\"" + path + "\"")) unclassified.add(path);
        }
        check("every route was found (" + routes + ")", routes > 40);
        check("every route belongs to a menu or is deliberately open: " + unclassified,
            unclassified.isEmpty());

        // The password route changes the caller's own password, not the
        // owner's. Before accounts there was one password and no difference;
        // with them, writing the owner's from any Settings account was a way
        // to take the owner's account outright.
        String pwRoute = web.substring(web.indexOf("private void handlePassword"),
            web.indexOf("private void handleUpdate"));
        check("changing a password asks who is asking", pwRoute.contains("who(ex)"));
        check("...and only the owner writes the owner's hash",
            pwRoute.indexOf("!me.owner()") > 0
                && pwRoute.indexOf("!me.owner()") < pwRoute.indexOf("webAdminPasswordHash"));
        check("...and a non-owner's new session is their own, not the owner's",
            pwRoute.contains("sessions.open(AlminConfig.get().webSessionMinutes, me.id())"));
        check("the owner's own new session says so",
            pwRoute.contains("sessions.open(AlminConfig.get().webSessionMinutes, \"owner\")"));

        check("the owner's username cannot be changed by anybody else",
            web.contains("OWNER_ONLY_KEYS") && web.contains("\"web-admin-username\""));
        check("nor how long a watched account's record is kept",
            web.substring(web.indexOf("OWNER_ONLY_KEYS"),
                web.indexOf("OWNER_ONLY_KEYS") + 600).contains("panel-audit-days"));

        // And the open ones are open on purpose, not by omission.
        check("the login is reachable without an account", open.contains("/api/login"));
        check("account management is not in the table — it is owner-only in the handler",
            !table.contains("\"/api/accounts\""));

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
