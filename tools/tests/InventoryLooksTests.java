import com.schecks.almin.InventoryLooks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The record of who looked in whose inventory.
 *
 * <p>This is the thing that makes the feature offerable at all, so what is
 * checked here is mostly that it cannot be got round: it is written before the
 * read, it is written for everybody, it survives a restart, it is readable by
 * anyone who opens the same player, and it holds no list of anybody's things.
 */
public class InventoryLooksTests {
    static int fail = 0;

    static void ck(String what, boolean ok, String saw) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (ok ? "" : "  -> " + saw));
        if (!ok) fail++;
    }

    static final String A = "11111111-1111-1111-1111-111111111111";
    static final String B = "22222222-2222-2222-2222-222222222222";

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("almin-looks");
        Path file = dir.resolve("config").resolve("almin").resolve("inventory-looks.json");
        InventoryLooks.init(dir);
        InventoryLooks.forget();

        ck("a fresh server has nobody to report", InventoryLooks.all().isEmpty(),
            String.valueOf(InventoryLooks.all().size()));

        InventoryLooks.record("mod", A, "Griefer");
        InventoryLooks.record("owner", A, "Griefer");
        InventoryLooks.record("mod", B, "Steve");

        List<InventoryLooks.Look> onA = InventoryLooks.forPlayer(A);
        ck("a look is written down", onA.size() == 2, String.valueOf(onA.size()));
        ck("...newest first, because that is the one somebody is looking for",
            onA.get(0).who().equals("owner"), onA.get(0).who());
        ck("...and it says who looked and whose it was",
            onA.get(0).uuid().equals(A) && onA.get(0).player().equals("Griefer"),
            onA.get(0).uuid());
        ck("...and when", onA.get(0).at() > 0, String.valueOf(onA.get(0).at()));

        // The point of the whole record: there is no account it is off for.
        ck("the owner is recorded like everybody else",
            onA.stream().anyMatch(l -> l.who().equals("owner")), "the owner slipped through");

        ck("one player's record is only that player's",
            InventoryLooks.forPlayer(B).size() == 1
                && InventoryLooks.forPlayer(B).get(0).player().equals("Steve"),
            String.valueOf(InventoryLooks.forPlayer(B).size()));

        ck("it is on the disk, not only in memory", Files.isRegularFile(file),
            "no file was written");

        // A restart must not be a way to clear it.
        InventoryLooks.init(dir);
        ck("...and it is still there after a restart", InventoryLooks.all().size() == 3,
            String.valueOf(InventoryLooks.all().size()));

        String written = Files.readString(file);
        ck("what it holds is when, who, and whose",
            written.contains("\"who\":\"mod\"") && written.contains("\"player\":\"Griefer\""),
            written);
        // A copy of somebody's things, kept forever in a second file, would be
        // a worse privacy problem than the one this record exists to solve.
        ck("...and never what was in the inventory",
            !written.contains("item") && !written.contains("count"), written);

        for (int i = 0; i < 700; i++) InventoryLooks.record("mod", A, "Griefer");
        ck("the record has a ceiling, so it cannot grow without end",
            InventoryLooks.all().size() == 600, String.valueOf(InventoryLooks.all().size()));

        InventoryLooks.forget();
        ck("a world reset takes the record with it",
            InventoryLooks.all().isEmpty() && !Files.exists(file),
            String.valueOf(InventoryLooks.all().size()));

        // ---- how it is wired in ----
        String web = Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
        int at = web.indexOf("private void handlePlayerInventory");
        String body = at < 0 ? "" : web.substring(at, Math.min(web.length(), at + 3000));
        ck("looking in an inventory is a route of its own", at >= 0, "no handler");
        ck("...which is a POST, because it is an act rather than a view",
            body.contains("!\"POST\".equals(ex.getRequestMethod())"), "it answers a GET");
        ck("...and needs a session good enough to change something",
            body.contains("requireAuthSecure(ex)"), "a read-only session could look");
        // Before, not after: a read that fails halfway still happened.
        ck("the record is written before the inventory is read",
            body.indexOf("InventoryLooks.record(") >= 0
                && body.indexOf("InventoryLooks.record(")
                     < body.indexOf("PlayerFile.inventory(server, id)"),
            "the look is recorded after the fact");
        ck("...and the panel is told nothing that would let it skip that",
            !body.contains("if (me.owner)") && !body.contains("skipRecord"),
            "there is a way past the record");

        // The pictures in the squares are the Players menu's to give. Reusing
        // /api/item would have let an account with the map open the icons for
        // a menu it cannot see.
        ck("the pictures in the squares are behind the same door as the sheet",
            web.contains("java.util.Map.entry(\"/api/player/icon\", \"players\")"),
            "the icon route is not menu-guarded, or guarded as something else");

        String src = Files.readString(
            Path.of("src/main/java/com/schecks/almin/InventoryLooks.java"));
        ck("there is no setting that turns the record off",
            !src.contains("Settings.") && !src.contains("enabled"),
            "the record has an off switch");

        String almin = Files.readString(Path.of("src/main/java/com/schecks/almin/Almin.java"));
        ck("the record is opened when the server starts",
            almin.contains("InventoryLooks.init("), "nothing loads it");
        String reset = Files.readString(
            Path.of("src/main/java/com/schecks/almin/WorldReset.java"));
        ck("...and cleared with the world it is about",
            reset.contains("InventoryLooks.forget()"), "a new world keeps the old record");

        System.out.println(fail == 0 ? "\nINVENTORY LOOK TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
