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

    /** The web server's source, for the handful of checks that belong there. */
    static String web0() throws Exception {
        return Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
    }

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

        // ---- handing over a shell, deliberately ----
        // What a restart runs is a command line on the host, so it is not part
        // of Settings: an account can hold all of Settings and still not be
        // one that gets to decide what this machine executes.
        check("nobody may change the start command to begin with",
            !Accounts.byUsername("moderator").canStartCommand());
        Accounts.setAccess(mod.id(), "settings", Accounts.WRITE);
        check("...not even with the whole of Settings",
            Accounts.byUsername("moderator").canWrite("settings")
                && !Accounts.byUsername("moderator").canStartCommand());
        Accounts.setStartCommand(mod.id(), true);
        check("...and it is a grant of its own",
            Accounts.byUsername("moderator").canStartCommand());
        check("the main account has it without being given it",
            Accounts.owner().canStartCommand());
        Accounts.setStartCommand(mod.id(), false);
        check("...and it can be taken back",
            !Accounts.byUsername("moderator").canStartCommand());
        Accounts.setStartCommand(mod.id(), true);

        // ---- it survives a restart ----
        Accounts.setAccess(mod.id(), "files", Accounts.READ);
        Accounts.init(dir);
        Accounts.Account back = Accounts.byUsername("moderator");
        check("the list is read back from disk", back != null);
        check("...with its grants", back.canRead("files") && !back.canWrite("files"));
        check("...and its recording setting", back.auditActivity());
        check("...and the start-command grant", back.canStartCommand());
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

        // ---- levels ----
        // "Account 1 at level 1 controls accounts 2 and 3 at level 2 or more."
        Accounts.create("chief", "chief-password-1", 1);
        Accounts.create("deputy", "deputy-password-1", 2);
        Accounts.create("helper2", "helper-password-1", 3);
        Accounts.Account chief = Accounts.byUsername("chief");
        Accounts.Account deputy = Accounts.byUsername("deputy");
        Accounts.Account helper2 = Accounts.byUsername("helper2");

        check("the owner is level 0", Accounts.owner().level() == Accounts.OWNER_RANK);
        check("the owner outranks everybody", Accounts.owner().outranks(chief)
            && Accounts.owner().outranks(deputy));
        check("level 1 controls level 2 and 3",
            chief.outranks(deputy) && chief.outranks(helper2));
        check("level 2 controls level 3", deputy.outranks(helper2));
        check("level 3 controls neither", !helper2.outranks(deputy) && !helper2.outranks(chief));
        check("equals are peers, neither way", !deputy.outranks(Accounts.byUsername("deputy")));
        check("nobody outranks themselves", !chief.outranks(chief));
        check("nobody outranks the owner", !chief.outranks(Accounts.owner()));

        check("a level cannot be better than the owner's",
            Accounts.rankOf(0) == Accounts.FIRST_RANK
                && Accounts.rankOf(-5) == Accounts.FIRST_RANK);
        check("...nor worse than the last", Accounts.rankOf(99999) == Accounts.LAST_RANK);
        Accounts.setRank(helper2.id(), 0);
        check("a stored account can never hold the owner's level",
            Accounts.byUsername("helper2").level() >= Accounts.FIRST_RANK);

        Accounts.setRank(helper2.id(), 5);
        check("a level can be changed", Accounts.byUsername("helper2").level() == 5);
        check("...and survives a restart", reload(dir) && Accounts.byUsername("helper2").level() == 5);

        // The gate is in WebUi; assert its shape, since that is where the
        // escalation would be if it were missing.
        String gate = Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
        String people = gate.substring(gate.indexOf("private void handleAccounts"),
            gate.indexOf("private String auditJson"));
        check("managing people needs Settings or the owner",
            people.contains("me.canWrite(\"settings\")"));
        check("every action but create checks who it is acting on",
            people.contains("!what.equals(\"create\")") && people.contains("me.outranks(target)"));
        check("a new account is made below its maker",
            people.contains("Accounts.rankOf(me.level() + 1)"));
        check("a level is refused unless it is below yours",
            people.contains("want <= me.level()"));
        check("the listing shows only those below you", gate.contains("if (!me.outranks(a)) continue;"));
        check("nobody hands out more than they hold",
            gate.contains("private static boolean grantable"));

        Accounts.delete(Accounts.byUsername("chief").id());
        Accounts.delete(Accounts.byUsername("deputy").id());
        Accounts.delete(Accounts.byUsername("helper2").id());

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

        // ---- what the browser is allowed to have written down ----
        // The panel reports selections, which means a browser is choosing what
        // goes into somebody's record. It chooses from a fixed set and the
        // server writes the sentence; if it could write the sentence, it could
        // write anything into the record of the person using it.
        java.lang.reflect.Method phrase =
            com.schecks.almin.WebUi.class.getDeclaredMethod("watchPhrase", String.class);
        phrase.setAccessible(true);
        check("a selection is described by the server, not the browser",
            "looked at one player".equals(phrase.invoke(null, "player")));
        // The panel says "still here" once a minute so the visit's end time
        // keeps moving. A line each time was most of what made this record
        // unreadable, and the visit already says how long they were in there.
        check("the minute heartbeat is not a line of its own",
            phrase.invoke(null, "here") == null);
        check("a kind nobody defined does not become a sentence",
            phrase.invoke(null, "<b>whatever they like</b>").equals("used the activity menu"));

        // A read-only account is exactly the account this record is kept for.
        // The route is a POST, so without an exception for it the gate would
        // refuse the very people it exists to record.
        check("the route that writes the record is not treated as a write",
            web0().contains("changing(ex.getRequestMethod()) && !WATCH_ROUTE.equals(route(ex))"));

        check("routes are described in words a person reads",
            com.schecks.almin.PanelAudit.describe("/api/insights", "POST").contains("model")
                && com.schecks.almin.PanelAudit.describe("/api/reset", "POST")
                    .contains("cleared"));

        // ---- the panel drawing itself is not a thing somebody did ----
        // The Activity tab polls the log, the paths, the map, the pictures of
        // the ground and the blocks behind a 3D view, several times a second
        // between them. A line each was a record nobody could read, with the
        // raw query string as its detail: "looked at the map
        // at=1788400917938&dim=overworld".
        check("polling writes nothing at all",
            com.schecks.almin.PanelAudit.describe("/api/activity", "GET") == null
                && com.schecks.almin.PanelAudit.describe("/api/track", "GET") == null
                && com.schecks.almin.PanelAudit.describe("/api/map", "GET") == null
                && com.schecks.almin.PanelAudit.describe("/bluemap", "GET") == null
                && com.schecks.almin.PanelAudit.describe("/api/scene/context", "GET") == null);
        check("...and the summary list, which is polled on a timer",
            com.schecks.almin.PanelAudit.describe("/api/insights", "GET") == null);
        check("...but anything that changes something is written even unnamed",
            "used the activity menu".equals(
                com.schecks.almin.PanelAudit.describe("/api/something/new", "POST")));
        check("the raw query string is no longer anybody's record",
            !web0().contains("about(ex)"));

        com.schecks.almin.PanelAudit.flush();
        com.schecks.almin.PanelAudit.init(dir);
        check("the record survives a restart",
            com.schecks.almin.PanelAudit.forUser("watched").size() == 2);

        // ---- visits ----
        // The panel polls the log, the paths and the map several times a
        // second between them. Recorded one line per request, an afternoon in
        // the Activity menu was thousands of identical lines with the one
        // entry worth reading buried inside. A stretch with the menu open is
        // one entry now, and what somebody chose to do hangs under it.
        com.schecks.almin.PanelAudit.visiting(seen);
        java.util.List<com.schecks.almin.PanelAudit.Entry> opened =
            com.schecks.almin.PanelAudit.forUser("watched");
        check("opening the menu is one entry", opened.size() == 3 && opened.get(0).visit());
        long startedAt = opened.get(0).at();

        for (int i = 0; i < 60; i++) com.schecks.almin.PanelAudit.visiting(seen);
        java.util.List<com.schecks.almin.PanelAudit.Entry> polled =
            com.schecks.almin.PanelAudit.forUser("watched");
        check("...and sixty more requests are not sixty more entries", polled.size() == 3);
        check("...they move its end time instead",
            polled.get(0).at() == startedAt && polled.get(0).until() >= startedAt);

        com.schecks.almin.PanelAudit.note(seen, "looked at one player", "Steve");
        com.schecks.almin.PanelAudit.visiting(seen);
        java.util.List<com.schecks.almin.PanelAudit.Entry> during =
            com.schecks.almin.PanelAudit.forUser("watched");
        check("what they chose to do lands inside the visit that was running",
            !during.get(0).visit() && during.get(1).visit()
                && during.get(0).at() >= during.get(1).at()
                && during.get(0).at() <= during.get(1).until());

        com.schecks.almin.PanelAudit.visiting(Accounts.owner());
        check("the owner's visits are not recorded either",
            com.schecks.almin.PanelAudit.forUser("admin").isEmpty());
        com.schecks.almin.PanelAudit.visiting(unseen);
        check("nor an account nobody asked to watch",
            com.schecks.almin.PanelAudit.forUser("unwatched").isEmpty());

        // Away long enough and coming back is a second visit rather than a
        // four-hour one. Done through the file so the visit flag is checked
        // surviving a write and a read as well.
        com.schecks.almin.PanelAudit.flush();
        Path auditFile = dir.resolve("config").resolve("almin")
            .resolve(com.schecks.almin.PanelAudit.fileName());
        long ago = System.currentTimeMillis() - 20 * 60_000L;
        Files.writeString(auditFile, Files.readString(auditFile)
            .replaceAll("\"at\":\\d+", "\"at\":" + ago)
            .replaceAll("\"until\":\\d+", "\"until\":" + ago));
        com.schecks.almin.PanelAudit.init(dir);
        int had = com.schecks.almin.PanelAudit.forUser("watched").size();
        boolean keptVisit = com.schecks.almin.PanelAudit.forUser("watched").stream()
            .anyMatch(com.schecks.almin.PanelAudit.Entry::visit);
        check("a visit is still a visit after a restart", keptVisit);
        com.schecks.almin.PanelAudit.visiting(seen);
        java.util.List<com.schecks.almin.PanelAudit.Entry> returned =
            com.schecks.almin.PanelAudit.forUser("watched");
        check("coming back after a while starts a new one",
            returned.size() == had + 1 && returned.get(0).visit());

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

    /** Reads the file back from disk. Returns true so it reads well inline. */
    static boolean reload(Path dir) {
        Accounts.init(dir);
        return true;
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
