import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.schecks.almin.PlayerFile;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What the world's own files say about one player.
 *
 * <p>Two files, neither of them Almin's: a statistics JSON the game writes as
 * it counts, and a save file holding what somebody is carrying. Both are read
 * and never written, so the checks below are about reading them correctly —
 * the numbers that are ranked, the spellings that changed in 1.20.5, and the
 * shapes a corrupt or truncated file arrives in.
 */
public class PlayerFileTests {
    static int fail = 0;

    static void ck(String what, boolean ok, String saw) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (ok ? "" : "  -> " + saw));
        if (!ok) fail++;
    }

    static long headline(PlayerFile.Stats s, String id) {
        for (PlayerFile.Count c : s.headline()) if (c.id().equals(id)) return c.value();
        return -1;
    }

    static List<String> ids(List<PlayerFile.Count> list) {
        return list.stream().map(PlayerFile.Count::id).toList();
    }

    public static void main(String[] args) throws Exception {
        // ---- statistics ----
        JsonObject root = JsonParser.parseString("""
            {"stats":{
              "minecraft:custom":{"minecraft:play_time":216000,"minecraft:deaths":4,
                                  "minecraft:mob_kills":30,"minecraft:walk_one_cm":250000},
              "minecraft:mined":{"minecraft:stone":4102,"minecraft:dirt":91,
                                 "minecraft:diamond_ore":7,"minecraft:sand":0},
              "minecraft:used":{"minecraft:torch":210},
              "minecraft:killed":{"minecraft:zombie":19}},
             "DataVersion":3953}""").getAsJsonObject();
        PlayerFile.Stats s = PlayerFile.read(root, 1234L);

        ck("a statistics file is read", s.found() && s.at() == 1234L, String.valueOf(s.at()));
        ck("the handful worth a tile is always there, in a fixed order",
            s.headline().size() == 8
                && s.headline().get(0).id().equals("minecraft:play_time"),
            String.valueOf(ids(s.headline())));
        ck("...with the numbers the file actually holds",
            headline(s, "minecraft:play_time") == 216000
                && headline(s, "minecraft:deaths") == 4,
            String.valueOf(ids(s.headline())));
        ck("...and a zero for one the file has never counted",
            headline(s, "minecraft:player_kills") == 0,
            String.valueOf(headline(s, "minecraft:player_kills")));

        ck("what somebody did most of comes first",
            ids(s.mined()).equals(List.of("minecraft:stone", "minecraft:dirt",
                                          "minecraft:diamond_ore")),
            String.valueOf(ids(s.mined())));
        // A zero is a thing the game wrote down and then undid; showing it as
        // one of somebody's top eight blocks would be a lie about a list.
        ck("...and something counted zero times is not on the list",
            !ids(s.mined()).contains("minecraft:sand"), String.valueOf(ids(s.mined())));
        ck("a kind of counting the file has none of comes back empty, not missing",
            s.crafted().isEmpty() && s.killed().size() == 1,
            s.crafted().size() + "/" + s.killed().size());

        // The file is written by the game, but it is on a disk anyone with the
        // server can edit, so nothing in it is trusted to be a number.
        JsonObject junk = JsonParser.parseString("""
            {"stats":{"minecraft:custom":{"minecraft:deaths":"lots"},
                      "minecraft:mined":"not an object"}}""").getAsJsonObject();
        PlayerFile.Stats bad = PlayerFile.read(junk, 0);
        ck("a statistic that is not a number is a zero rather than a crash",
            headline(bad, "minecraft:deaths") == 0 && bad.mined().isEmpty(),
            String.valueOf(headline(bad, "minecraft:deaths")));

        ck("an id is turned into words",
            PlayerFile.pretty("minecraft:polished_blackstone_bricks")
                .equals("Polished blackstone bricks"),
            PlayerFile.pretty("minecraft:polished_blackstone_bricks"));
        ck("...including one from a mod, which has a different namespace",
            PlayerFile.pretty("create:cogwheel").equals("Cogwheel"),
            PlayerFile.pretty("create:cogwheel"));

        // ---- where a slot is ----
        ck("the first nine slots are the hotbar",
            PlayerFile.where(0).equals("hotbar") && PlayerFile.where(8).equals("hotbar"),
            PlayerFile.where(8));
        ck("...and the rest of the grid is the pack",
            PlayerFile.where(9).equals("pack") && PlayerFile.where(35).equals("pack"),
            PlayerFile.where(35));
        // The game's own numbering, which is not contiguous. Getting this
        // wrong puts somebody's helmet in a square of the backpack.
        ck("...and armour is counted from the feet up, at 100",
            PlayerFile.where(100).equals("feet") && PlayerFile.where(101).equals("legs")
                && PlayerFile.where(102).equals("chest") && PlayerFile.where(103).equals("head"),
            PlayerFile.where(100) + "/" + PlayerFile.where(103));
        ck("...and the off hand is the one negative slot there is",
            PlayerFile.where(-106).equals("offhand"), PlayerFile.where(-106));
        ck("...and a slot number that means nothing is vague rather than wrong",
            PlayerFile.where(36).equals("carried") && PlayerFile.where(-1).equals("carried")
                && PlayerFile.where(104).equals("carried"),
            PlayerFile.where(104));

        // ---- a save file ----
        Path dir = Files.createTempDirectory("almin-playerdata");
        Path dat = dir.resolve("player.dat");

        CompoundTag tag = new CompoundTag();
        ListTag inv = new ListTag();
        CompoundTag now = new CompoundTag();
        now.putString("id", "minecraft:diamond");
        now.putInt("count", 12);              // 1.20.5 onwards
        now.putByte("Slot", (byte) 0);
        inv.add(now);
        CompoundTag old = new CompoundTag();
        old.putString("id", "minecraft:cobblestone");
        old.putInt("Count", 64);              // what an older world wrote
        old.putByte("Slot", (byte) 20);
        inv.add(old);
        CompoundTag nameless = new CompoundTag();
        nameless.putInt("count", 1);          // no id at all
        inv.add(nameless);
        CompoundTag boots = new CompoundTag();
        boots.putString("id", "minecraft:diamond_boots");
        boots.putInt("count", 1);
        boots.putByte("Slot", (byte) 100);    // where an older world puts armour
        inv.add(boots);
        tag.put("Inventory", inv);
        CompoundTag worn = new CompoundTag();  // where a newer one does
        CompoundTag helm = new CompoundTag();
        helm.putString("id", "minecraft:iron_helmet");
        helm.putInt("count", 1);
        worn.put("head", helm);
        tag.put("equipment", worn);
        ListTag ender = new ListTag();
        CompoundTag kept = new CompoundTag();
        kept.putString("id", "minecraft:netherite_ingot");
        kept.putInt("count", 3);
        kept.putByte("Slot", (byte) 4);
        ender.add(kept);
        tag.put("EnderItems", ender);
        NbtIo.writeCompressed(tag, dat);

        PlayerFile.Gear gear = PlayerFile.savedGear(dat);
        ck("a save file is read without the player being online",
            !gear.live() && gear.any() && gear.items().size() == 5,
            gear.items().size() + " item(s)");
        ck("both spellings of a stack size are understood",
            gear.items().get(0).count() == 12 && gear.items().get(1).count() == 64,
            gear.items().get(0).count() + "/" + gear.items().get(1).count());
        ck("...and a slot number says where the stack was",
            gear.items().get(0).where().equals("hotbar")
                && gear.items().get(1).where().equals("pack"),
            gear.items().get(0).where() + "/" + gear.items().get(1).where());
        ck("the ender chest is read too, and named as itself",
            gear.items().stream().anyMatch(i -> i.where().equals("ender chest")
                && i.id().equals("minecraft:netherite_ingot")),
            String.valueOf(gear.items()));
        ck("armour in the old place lands on the body",
            gear.items().stream().anyMatch(i -> i.where().equals("feet")
                && i.id().equals("minecraft:diamond_boots")),
            String.valueOf(gear.items()));
        ck("...and so does armour in the new one",
            gear.items().stream().anyMatch(i -> i.where().equals("head")
                && i.id().equals("minecraft:iron_helmet")),
            String.valueOf(gear.items()));
        ck("an entry with nothing in it is skipped rather than drawn empty",
            gear.items().stream().noneMatch(i -> i.id().isEmpty()),
            String.valueOf(gear.items()));

        Files.writeString(dat, "this is not a save file");
        PlayerFile.Gear broken = PlayerFile.savedGear(dat);
        ck("a save file that will not open is nothing, not a crash",
            !broken.any() && !broken.live(), String.valueOf(broken.items().size()));
        ck("...and so is one that is not there at all",
            !PlayerFile.savedGear(dir.resolve("nobody.dat")).any(), "something came back");

        // ---- what the class refuses to do ----
        String src = Files.readString(Path.of("src/main/java/com/schecks/almin/PlayerFile.java"));
        ck("nothing here writes to the world",
            !src.contains("Files.write") && !src.contains("NbtIo.write")
                && !src.contains("setItem("),
            "PlayerFile has a way to change the world");

        System.out.println(fail == 0 ? "\nPLAYER FILE TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
