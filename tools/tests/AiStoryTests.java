import com.schecks.almin.ActivityEntry;
import com.schecks.almin.AiStory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A history, and what it costs to have one.
 *
 * <p>The model half needs a model, so it is not run here. What is run is
 * everything around it: which days there are, which of them are worth a
 * sentence, and — the part with money attached — that nothing is written
 * without being asked and nothing already written is written twice.
 *
 * <p>The tidying is checked hardest, because it is the only place a small
 * model's habits get to reach the page. A model asked for one sentence answers
 * with a paragraph, a bullet, or "Here is the sentence:" and then the
 * sentence, and all three have to come out as the sentence.
 */
public class AiStoryTests {
    static int fail = 0;

    static void ck(String what, boolean ok, String saw) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (ok ? "" : "  -> " + saw));
        if (!ok) fail++;
    }

    static final long DAY = 86_400_000L;

    /** Rows on the evening of {@code daysAgo} days ago. */
    static void day(List<ActivityEntry> out, int daysAgo, int times) {
        long at = AiStory.dayOf(System.currentTimeMillis() - daysAgo * DAY) + 19 * 3600_000L;
        for (int i = 0; i < times; i++) {
            out.add(new ActivityEntry(at + i * 1000L, i % 2 == 0 ? "Ivy" : "Rune",
                "uuid-" + (i % 2), "break", "Stone", "overworld", 10 + i, 64, 20, 1));
        }
    }

    static AiStory.Day at(AiStory.Story s, int daysAgo) {
        long want = AiStory.dayOf(System.currentTimeMillis() - daysAgo * DAY);
        for (AiStory.Day d : s.days()) if (d.at() == want) return d;
        return null;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("almin-story");
        AiStory.init(root);

        // ---- which days there are ----
        {
            List<ActivityEntry> rows = new ArrayList<>();
            day(rows, 1, 400);
            day(rows, 2, 300);
            day(rows, 3, 4);      // a handful of rows is not a day worth a line
            AiStory.Story s = AiStory.of(rows);

            ck("a day of activity is a day in the history", s.days().size() == 3,
                s.days().size() + " days");
            ck("...newest first, because that is where a reader starts",
                s.days().get(0).at() > s.days().get(2).at(),
                String.valueOf(s.days().get(0).at()));
            AiStory.Day quiet = at(s, 3);
            ck("...and a day with four rows in it is still shown, unwritten",
                quiet != null && !quiet.written() && quiet.events() == 4,
                quiet == null ? "missing" : quiet.events() + " events");
            ck("only the days worth a sentence are counted as missing one",
                s.missing() == 2, String.valueOf(s.missing()));
            ck("nothing is written until somebody asks",
                s.days().stream().noneMatch(AiStory.Day::written), "something wrote itself");
            ck("...and how many people were there is counted from the rows",
                at(s, 1) != null && at(s, 1).players() == 2,
                at(s, 1) == null ? "missing" : String.valueOf(at(s, 1).players()));
        }
        {
            ck("no log at all is no history, rather than a failure",
                AiStory.of(List.of()).days().isEmpty(), "it invented a day");
        }

        // ---- what the model says, and what is kept of it ----
        ck("a plain sentence comes through as it is",
            AiStory.tidy("Ivy dug a shaft to bedrock.")
                .equals("Ivy dug a shaft to bedrock."),
            AiStory.tidy("Ivy dug a shaft to bedrock."));
        ck("a model that answered with a list is answering with its first item",
            AiStory.tidy("- Ivy dug a shaft to bedrock.\n- Rune built a wall.")
                .equals("Ivy dug a shaft to bedrock."),
            AiStory.tidy("- Ivy dug a shaft to bedrock.\n- Rune built a wall."));
        ck("...however it numbered them",
            AiStory.tidy("1. Ivy dug a shaft.").equals("Ivy dug a shaft."),
            AiStory.tidy("1. Ivy dug a shaft."));
        ck("a preamble is not the sentence",
            AiStory.tidy("Here is the sentence:\nIvy dug a shaft to bedrock.")
                .equals("Ivy dug a shaft to bedrock."),
            AiStory.tidy("Here is the sentence:\nIvy dug a shaft to bedrock."));
        ck("quotation marks around the whole of it come off",
            AiStory.tidy("\"Ivy dug a shaft to bedrock.\"")
                .equals("Ivy dug a shaft to bedrock."),
            AiStory.tidy("\"Ivy dug a shaft to bedrock.\""));
        ck("a model that kept going is cut after the first sentence",
            AiStory.tidy("Ivy dug a shaft. She then built a wall. Then she left.")
                .equals("Ivy dug a shaft."),
            AiStory.tidy("Ivy dug a shaft. She then built a wall. Then she left."));
        {
            // A number ends in a full stop too, and cutting there would leave
            // "Ivy broke 1" — a sentence that says something false.
            String said = AiStory.tidy("Ivy broke 1.5 stacks of stone and went home.");
            ck("...but a decimal point does not end a sentence",
                said.equals("Ivy broke 1.5 stacks of stone and went home."), said);
        }
        {
            String long_ = "a".repeat(400);
            ck("a model that ignored the word limit is trimmed rather than trusted",
                AiStory.tidy(long_).length() <= 240, String.valueOf(AiStory.tidy(long_).length()));
        }
        ck("nothing said is nothing written",
            AiStory.tidy("").isEmpty() && AiStory.tidy(null).isEmpty()
                && AiStory.tidy("   \n  ").isEmpty(), "empty came back as something");

        // ---- the money ----
        {
            String src = Files.readString(Path.of("src/main/java/com/schecks/almin/AiStory.java"));
            ck("a day is written a few at a time, not a fortnight at once",
                src.contains("private static final int STEP = 4"),
                "the step is gone, so opening the tab could fire a fortnight of requests");
            ck("a finished day is never written twice",
                src.contains("isToday(day)") && src.contains("A day that is over never changes"),
                "a day that cannot have changed would be re-billed");
            ck("what is written survives a restart",
                src.contains("ai-story.json") && src.contains("Files.writeString"),
                "the history is in memory only, so a restart re-bills the lot");
            ck("one request at a time",
                src.contains("running.compareAndSet(false, true)"),
                "two presses would write the same day twice");

            String web = Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
            ck("reading the history is a read and writing it is a write",
                web.contains("boolean write = \"POST\".equals(ex.getRequestMethod())")
                    && web.contains("if (write && noModel(ex)) return;"),
                "opening the tab is billed, or an account barred from the model can write");
            ck("the history is behind the AI menu",
                web.contains("java.util.Map.entry(\"/api/story\", \"ai\")"),
                "the history route is not menu-guarded");
            // One text about the whole server cannot be written per reader:
            // whatever one reader's narrower slice produced would become the
            // line everybody else sees.
            ck("a reader who may see only their own activity is not shown it",
                web.contains("me.ownActivityOnly()") && web.contains("not written per account"),
                "a history built from one account's rows would be shared with everybody");
            ck("...nor is one written from chat shown to a reader kept from chat",
                web.contains("me.chatHidden() && AlminConfig.get().aiSendChat"),
                "a paraphrase of chat would reach an account not shown chat");

            String reset = Files.readString(
                Path.of("src/main/java/com/schecks/almin/WorldReset.java"));
            ck("clearing the log clears the history written from it",
                reset.contains("AiStory.forget()"),
                "a history of a log that has gone would outlive it");
        }

        AiStory.forget();
        ck("forgetting takes the file with it",
            !Files.exists(root.resolve("config").resolve("almin").resolve("ai-story.json")),
            "the file survived");

        System.out.println(fail == 0 ? "\nAI STORY TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
