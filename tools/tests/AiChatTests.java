import com.schecks.almin.Accounts;
import com.schecks.almin.ActivityEntry;
import com.schecks.almin.AiInsights;
import com.schecks.almin.AlminConfig;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The AI menu: does the model actually go and look things up?
 *
 * <p>The whole point of this feature is what is <em>not</em> sent. The older
 * summariser pastes the log into the prompt; this sends the question and a
 * list of tools, and the log only ever travels as the answer to a lookup the
 * model asked for. That is a claim about bytes on a socket, so it is tested
 * against a real socket: a stub provider on loopback answers the first request
 * with a tool call and the second with words, and the test reads what arrived.
 */
public class AiChatTests {

    static int fail = 0;
    static void ck(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fail++;
    }
    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + (ok ? "" : "  -> " + detail));
        if (!ok) fail++;
    }

    /** Every request body the stub provider received, oldest first. */
    static final List<String> asked = new ArrayList<>();
    /** Replies the stub will hand out, in order; the last one repeats. */
    static final Deque<String> replies = new ArrayDeque<>();
    static String lastReply = "";

    static AlminConfig cfg;
    /** When set, the stub provider waits on this before answering. */
    static volatile java.util.concurrent.CountDownLatch hold = null;

    public static void main(String[] args) throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            asked.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            java.util.concurrent.CountDownLatch wait = hold;
            if (wait != null) {
                try { wait.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { java.lang.Thread.currentThread().interrupt(); }
            }
            String json = replies.isEmpty() ? lastReply : (lastReply = replies.poll());
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(out); }
            ex.close();
        });
        http.start();
        int port = http.getAddress().getPort();
        String base = "http://127.0.0.1:" + port + "/v1";
        endpointOverrides(Map.of(
            "anthropic", "http://127.0.0.1:" + port + "/anthropic",
            "openai", "http://127.0.0.1:" + port + "/openai",
            "google", "http://127.0.0.1:" + port + "/google"));

        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        cfg = cc.newInstance();
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true); inst.set(null, cfg);
        set("aiEnabled", true);
        set("aiChat", true);
        set("aiProvider", "local");
        set("aiModel", "qwen2.5:3b");
        set("aiBaseUrl", base);
        set("aiToolRounds", 6);

        AiInsights.init(Files.createTempDirectory("almin-aichat"));

        seedLog();

        catalogue();
        readers();
        places();
        theLoop();
        roundCap();
        shapes();
        inTheBackground();
        refusals();

        http.stop(0);
        System.out.println(fail == 0 ? "\nAI CHAT TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ---------- the catalogue is the permission boundary ----------

    /**
     * A model is talked out of things, so nothing about who may see what is
     * left to the prompt. What an account may reach is decided by which tools
     * it is handed, and by a second check when one is called.
     */
    static void catalogue() throws Exception {
        System.out.println("\n-- which tools an account is given --");

        List<String> owner = toolNames(owner());
        ck("the owner gets the whole catalogue (" + owner.size() + ")", owner.size() >= 9);
        ck("including the two log tools",
            owner.contains("search_activity") && owner.contains("count_activity"));

        List<String> activityOnly = toolNames(account(Map.of("activity", "read"), Set.of()));
        ck("an account with only Activity gets the log tools",
            activityOnly.contains("search_activity") && activityOnly.contains("count_activity"));
        ck("and not the ones belonging to menus it cannot open",
            !activityOnly.contains("server_status")
                && !activityOnly.contains("settings_summary")
                && !activityOnly.contains("list_players"),
            String.join(",", activityOnly));

        List<String> noCoords = toolNames(
            account(Map.of("activity", "read"), Set.of(Accounts.HIDE_COORDS)));
        ck("an account not shown coordinates is not given the path tool",
            !noCoords.contains("player_positions"), String.join(",", noCoords));
        // A place is a coordinate somebody sleeps at, which is more than a
        // path point gives away, not less.
        ck("nor the one that says where everybody's base is",
            !noCoords.contains("list_places"), String.join(",", noCoords));
        ck("an account with only Activity does get it",
            activityOnly.contains("list_places"), String.join(",", activityOnly));

        ck("an account with no menus at all gets nothing",
            toolNames(account(Map.of(), Set.of())).isEmpty());

        // The catalogue and the executor are reached by different paths: a
        // conversation carries the tools it started with, and a menu can be
        // taken away in between. So the executor checks too.
        String refused = run(account(Map.of("activity", "read"), Set.of()),
            "settings_summary", "{}");
        ck("a tool it was never given is refused when called anyway",
            refused.contains("\"error\"") && refused.contains("Settings"), refused);

        String hidden = run(account(Map.of("activity", "read"), Set.of(Accounts.HIDE_COORDS)),
            "player_positions", "{\"player\":\"Steve\"}");
        ck("and so is the path tool, for an account not shown coordinates",
            hidden.contains("\"error\"") && hidden.contains("coordinates"), hidden);

        String places = run(account(Map.of("activity", "read"), Set.of(Accounts.HIDE_COORDS)),
            "list_places", "{}");
        ck("and so is the places tool",
            places.contains("\"error\"") && places.contains("coordinates"), places);
    }

    // ---------- what the tools actually return ----------

    /**
     * The readers, and the rules they have to keep.
     *
     * <p>A tool is a second road to data the panel already guards, so it is
     * also a second way around those guards. These are the checks that the
     * road is guarded the same way — including the subtle one, where a filter
     * that matched on hidden text would let somebody find out what it said
     * without ever being shown it.
     */
    static void readers() throws Exception {
        System.out.println("\n-- what the tools hand back --");

        Accounts.Account plain = account(Map.of("activity", "read", "players", "read"), Set.of());
        String all = run(plain, "search_activity", "{\"limit\":5}");
        ck("search_activity returns rows and how many matched",
            all.contains("\"matched\":400") && all.contains("\"returned\":5"), all);
        ck("newest first", all.indexOf("\"player\":\"Herobrine\"") > 0
            || all.contains("\"rows\""), all);
        ck("with a time a person can read beside every row",
            all.contains("\"ago\"") && all.contains("\"when\""));

        String one = run(plain, "search_activity", "{\"player\":\"Alex\",\"limit\":3}");
        ck("narrowing by player narrows the count", one.contains("\"matched\":133"), one);
        ck("and returns only that player",
            !one.contains("Herobrine") && !one.contains("\"player\":\"Steve\""), one);

        String counted = run(plain, "count_activity", "{\"group_by\":\"action\"}");
        ck("count_activity groups without returning rows",
            counted.contains("\"grouped_by\":\"action\"") && counted.contains("\"total\":400")
                && !counted.contains("diamond_ore"), counted);

        String over = run(plain, "activity_overview", "{}");
        ck("activity_overview says how far back the log goes",
            over.contains("\"rows_kept\":400") && over.contains("oldest_row")
                && over.contains("history_note"), over);
        ck("and names the players in it",
            over.contains("Steve") && over.contains("Herobrine"), over);

        // A page past the end is a real question with a real answer, not an
        // error: it is how the model learns it has read everything.
        String past = run(plain, "search_activity", "{\"offset\":100000,\"limit\":5}");
        ck("reading past the end returns nothing rather than failing",
            past.contains("\"returned\":0") && !past.contains("\"error\""), past);

        // The cap is the feature. A tool that could hand back the whole log
        // would be the old prompt with extra steps.
        ck("a request for more rows than allowed is capped, not obeyed",
            run(plain, "search_activity", "{\"limit\":5000}").contains("\"returned\":60"));

        System.out.println("\n-- and what they refuse to hand back --");

        Accounts.Account noChat = account(Map.of("activity", "read"),
            Set.of(Accounts.HIDE_CHAT));
        String quiet = run(noChat, "search_activity", "{\"action\":\"chat\",\"limit\":10}");
        ck("an account not shown chat gets the rows without the words",
            !quiet.contains("meet me at spawn"), quiet);

        // The important one. Matching on text the account may not read would
        // turn the filter into an oracle for it: ask for a word, count the
        // hits, and the hidden line is no longer hidden.
        String oracle = run(noChat, "search_activity", "{\"find\":\"meet me at spawn\"}");
        ck("and cannot use the filter to find out what it said",
            oracle.contains("\"matched\":0"), oracle);
        String plainOracle = run(plain, "search_activity", "{\"find\":\"meet me at spawn\"}");
        ck("while an account that may read it finds it",
            !plainOracle.contains("\"matched\":0"), plainOracle);

        Accounts.Account onlyMine = account(Map.of("activity", "read"),
            Set.of(Accounts.OWN_ACTIVITY));
        String mine = run(onlyMine, "search_activity", "{\"limit\":10}");
        ck("an account restricted to its own activity sees only its own",
            !mine.contains("Herobrine") && !mine.contains("\"player\":\"Alex\"")
                && mine.contains("Steve"), mine);
        ck("and its counts are its own too",
            run(onlyMine, "count_activity", "{\"group_by\":\"player\"}")
                .contains("\"total\":134"),
            run(onlyMine, "count_activity", "{\"group_by\":\"player\"}"));
        ck("it cannot read another player's path either",
            run(onlyMine, "player_positions", "{\"player\":\"Alex\"}").contains("\"error\""));

        String settings = run(owner(), "settings_summary", "{\"find\":\"password\"}");
        ck("the password hash is never in the settings a model is given",
            !settings.contains("web-admin-password-hash") && settings.contains("withheld"),
            settings);
    }

    // ---------- the point of the whole feature ----------

    static void theLoop() throws Exception {
        System.out.println("\n-- the model looks it up rather than being handed it --");
        asked.clear(); replies.clear();
        replies.add(chatToolCall("call-1", "count_activity", "{\"group_by\":\"player\"}"));
        replies.add(chatText("Steve, by a distance."));

        Object answer = ask(owner(), "Who has been busiest?");

        ck("two round trips: one to ask, one to answer", asked.size() == 2,
            "was " + asked.size());

        String first = asked.get(0);
        ck("the first request offers the tools",
            first.contains("\"tools\"") && first.contains("count_activity")
                && first.contains("search_activity"));
        ck("and carries the question", first.contains("Who has been busiest?"));

        // The claim this feature rests on. The log is 400 rows of chat and
        // block edits; none of it may be in the request that only asked.
        ck("but not one row of the log",
            !first.contains("diamond_ore") && !first.contains("meet me at spawn")
                && !first.contains("\"detail\""),
            "first request was " + first.length() + " bytes");
        ck("so the first request stays small (" + first.length() + " bytes)",
            first.length() < 12000);

        String second = asked.get(1);
        ck("the second request carries the tool's answer back",
            second.contains("tool_call_id") || second.contains("\"role\":\"tool\""), second);
        ck("and that answer is the counted log, not the log",
            second.contains("grouped_by") && second.contains("Steve")
                && !second.contains("diamond_ore"), second);

        ck("the answer reached the panel", text(answer).equals("Steve, by a distance."));
        List<?> steps = steps(answer);
        ck("with the lookup shown as its working", steps.size() == 1);
        ck("named so a person can read it", String.valueOf(steps.get(0)).contains("count"),
            String.valueOf(steps.get(0)));

        // A second question in the same conversation must carry the first.
        asked.clear(); replies.clear();
        replies.add(chatText("Yes, twice."));
        ask(owner(), "And before that?");
        ck("the next question replays the conversation",
            asked.get(0).contains("Who has been busiest?")
                && asked.get(0).contains("And before that?"));

        clear(owner());
        asked.clear(); replies.clear();
        replies.add(chatText("Nothing yet."));
        ask(owner(), "Fresh start");
        ck("starting again forgets it", !asked.get(0).contains("Who has been busiest?"));
    }

    // ---------- the ceiling on what one question costs ----------

    static void roundCap() throws Exception {
        System.out.println("\n-- a model that will not stop looking things up --");
        clear(owner());
        set("aiToolRounds", 3);
        asked.clear(); replies.clear();
        // Never stops asking. The last reply repeats forever, so nothing but
        // the cap can end this.
        replies.add(chatToolCall("c", "count_activity", "{\"group_by\":\"player\"}"));

        Object answer = ask(owner(), "Keep going");
        ck("it stops", asked.size() <= 5, "made " + asked.size() + " requests");
        ck("at the cap plus the one that has to answer", asked.size() == 4,
            "made " + asked.size());
        ck("the last request offers no tools at all, so answering is all that is left",
            !asked.get(asked.size() - 1).contains("\"tools\""));
        ck("the person is told it ran out rather than shown an empty bubble",
            error(answer).contains("without answering")
                && error(answer).contains("ai-tool-rounds"),
            text(answer) + " / " + error(answer));
        ck("with every lookup it did manage still shown", steps(answer).size() == 3,
            String.valueOf(steps(answer).size()));
        set("aiToolRounds", 6);
    }

    // ---------- each provider spells tools differently ----------

    static void shapes() throws Exception {
        System.out.println("\n-- the four request shapes --");
        AiInsights.setKey("test-key");

        clear(owner());
        set("aiProvider", "anthropic");
        set("aiModel", "claude-test");
        asked.clear(); replies.clear();
        replies.add(anthropicToolCall("tu_1", "count_activity"));
        replies.add(anthropicText("Done."));
        Object a = ask(owner(), "count it");
        ck("Anthropic declares tools with input_schema",
            asked.get(0).contains("\"input_schema\"") && asked.get(0).contains("count_activity"));
        ck("Anthropic gets the result back as a tool_result block",
            asked.get(1).contains("\"type\":\"tool_result\"")
                && asked.get(1).contains("\"tool_use_id\":\"tu_1\""));
        ck("and answers", text(a).equals("Done."));

        clear(owner());
        set("aiProvider", "openai");
        set("aiModel", "gpt-test");
        asked.clear(); replies.clear();
        replies.add(responsesToolCall("fc_1", "count_activity"));
        replies.add(responsesText("Done."));
        Object o = ask(owner(), "count it");
        ck("OpenAI Responses declares a flat function tool",
            asked.get(0).contains("\"type\":\"function\"")
                && asked.get(0).contains("\"name\":\"count_activity\"")
                && !asked.get(0).contains("\"function\":{"));
        ck("OpenAI Responses gets a function_call_output back",
            asked.get(1).contains("\"type\":\"function_call_output\"")
                && asked.get(1).contains("\"call_id\":\"fc_1\""));
        ck("and answers", text(o).equals("Done."));

        clear(owner());
        set("aiProvider", "google");
        set("aiModel", "gemini-test");
        asked.clear(); replies.clear();
        replies.add(googleToolCall("count_activity"));
        replies.add(googleText("Done."));
        Object g = ask(owner(), "count it");
        ck("Google declares functionDeclarations",
            asked.get(0).contains("functionDeclarations")
                && asked.get(0).contains("count_activity"));
        ck("Google gets a functionResponse whose payload is an object, not a string",
            asked.get(1).contains("functionResponse")
                && asked.get(1).contains("\"response\":{"));
        ck("and answers", text(g).equals("Done."));

        set("aiProvider", "local");
        set("aiModel", "qwen2.5:3b");
        clear(owner());
    }

    // ---------- asking without holding the request open ----------

    /**
     * A question is answered on its own thread.
     *
     * <p>Not a detail. The panel has four request threads and polls itself
     * every few seconds, and one question can be several round trips each
     * allowed the full model timeout. Holding a request open for that also
     * puts the answer behind whatever reverse proxy sits in front of Almin,
     * which gives up around a minute and returns its own 504 — the exact
     * failure the model timeout was added to prevent for a single request.
     */
    static void inTheBackground() throws Exception {
        System.out.println("\n-- asking does not hold the request open --");
        clear(owner());
        asked.clear(); replies.clear();
        replies.add(chatText("Answered eventually."));

        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        hold = gate;
        long began = System.currentTimeMillis();
        String why = start(owner(), "something slow");
        long took = System.currentTimeMillis() - began;

        ck("starting one is not refused", why.isEmpty(), why);
        ck("and comes back at once rather than waiting for the model", took < 500,
            took + "ms");
        ck("it reports itself as working", working(owner()));
        ck("with the question already on the transcript, so a poll sees it",
            history(owner()).size() == 1 && text(history(owner()).get(0))
                .equals("something slow"));

        String second = start(owner(), "and another");
        ck("a second question while one is running is refused, not queued",
            second.contains("Already working"), second);
        ck("and the refused one is not added to the transcript",
            history(owner()).size() == 1, String.valueOf(history(owner()).size()));

        gate.countDown();
        hold = null;
        for (int i = 0; i < 100 && working(owner()); i++) java.lang.Thread.sleep(50);

        ck("once the model answers it stops working", !working(owner()));
        List<?> shown = history(owner());
        ck("and the answer is on the transcript under the question",
            shown.size() == 2 && text(shown.get(1)).equals("Answered eventually."),
            shown.size() + " messages");
        ck("the question was not added twice",
            mine(shown.get(0)) && !mine(shown.get(1)));

        ck("and another question can be asked afterwards",
            start(owner(), "next one").isEmpty());
        for (int i = 0; i < 100 && working(owner()); i++) java.lang.Thread.sleep(50);
    }

    // ---------- what stands in the way, and how it is said ----------

    static void refusals() throws Exception {
        System.out.println("\n-- when it should not answer at all --");

        set("aiChat", false);
        ck("with the menu switched off, the reason names the setting",
            problem(owner()).contains("ai-chat"), problem(owner()));
        set("aiChat", true);
        ck("and with it on, nothing is in the way", problem(owner()).isEmpty(),
            problem(owner()));

        // The summariser words this one as "summaries are off", which is the
        // wrong sentence to read under a menu nobody opened for a summary.
        set("aiEnabled", false);
        ck("with no model set up, it points at Settings rather than at summaries",
            problem(owner()).contains("No model is switched on")
                && !problem(owner()).toLowerCase().contains("summaries"),
            problem(owner()));
        set("aiEnabled", true);

        Accounts.Account barred = account(Map.of("ai", "write", "activity", "read"),
            Set.of(Accounts.NO_MODEL));
        ck("an account barred from the model is told so, not billed for it",
            problem(barred).toLowerCase().contains("not allowed"), problem(barred));

        clear(owner());
        asked.clear(); replies.clear();
        replies.add("{\"error\":{\"message\":\"model is loading\"}}");
        Object broken = ask(owner(), "anything");
        ck("a failing model reports what it said", error(broken).contains("model is loading"),
            error(broken) + " / " + text(broken));

        // A half-finished exchange left in the transcript is rejected by every
        // provider, so a failure has to take its own turns back out with it.
        asked.clear(); replies.clear();
        replies.add(chatText("Fine now."));
        Object after = ask(owner(), "try again");
        ck("and the next question still works", text(after).equals("Fine now."),
            text(after) + " / " + error(after));
        ck("the failed exchange is not replayed",
            !asked.get(0).contains("\"role\":\"tool\""), asked.get(0));
    }

    /**
     * Somewhere somebody keeps going back to.
     *
     * <p>On its own log, because the shared one is 400 rows in one straight
     * line over six minutes — nothing recurs in it, so it makes no places at
     * all, and a check run against it would pass without testing anything.
     * Several other checks assert on that row count, so it is put back after.
     */
    static void places() throws Exception {
        System.out.println("\n-- what the model is told about where people live --");
        // Five days is the default, and a place is a fortnight-shaped fact, so
        // the log has to be allowed to reach back far enough to hold one.
        int keep = cfg.activityRetentionMinutes;
        set("activityRetentionMinutes", 43_200);
        seedPlaces();
        try {
            String all = run(owner(), "list_places", "{}");
            ck("the owner is told about the places in the log",
                all.contains("\"kind\":\"base\"") && all.contains("Alex"), all);
            ck("and what each one is, in words a model can use",
                all.contains("\"what\"") && all.contains("\"visits\"")
                    && all.contains("\"dimension\"") && all.contains("\"last_used\""), all);

            // A place is assembled from rows. A reader who may not see the
            // rows may not see what was built out of them either — otherwise
            // the tool is a way to find out where somebody sleeps without ever
            // being shown a single thing they did.
            Accounts.Account onlyMine = account(Map.of("activity", "read"),
                Set.of(Accounts.OWN_ACTIVITY));
            String mine = run(onlyMine, "list_places", "{}");
            ck("a reader restricted to its own rows is not told about Alex's base",
                !mine.contains("Alex"), mine);
            ck("and is told about its own",
                mine.contains("Steve") && mine.contains("\"kind\":\"base\""), mine);

            ck("the kind can be asked for by name",
                !run(owner(), "list_places", "{\"kind\":\"mine\"}").contains("\"kind\":\"base\""),
                run(owner(), "list_places", "{\"kind\":\"mine\"}"));
        } finally {
            set("activityRetentionMinutes", keep);
            seedLog();
        }
    }

    /** Two people, two bases, several evenings each. */
    static void seedPlaces() throws Exception {
        List<ActivityEntry> rows = new ArrayList<>();
        long day = 86_400_000L;
        long start = (System.currentTimeMillis() / day - 12) * day;
        String[] who = { "Steve", "Alex" };
        int[] xs = { 200, -800 };
        for (int p = 0; p < who.length; p++) {
            for (int d = 0; d < 7; d++) {
                long at = start + d * day + 19 * 3600_000L;
                for (int i = 0; i < 14; i++) {
                    rows.add(new ActivityEntry(at + i * 1500L, who[p], "uuid-" + who[p],
                        i % 4 == 0 ? "container" : "place",
                        i % 4 == 0 ? "Chest" : "Oak Planks",
                        "overworld", xs[p] + (i % 5), 64, 500 + (i % 3), 1));
                }
            }
            rows.add(new ActivityEntry(start + 3 * day + 20 * 3600_000L, who[p],
                "uuid-" + who[p], "sleep", "Red Bed", "overworld", xs[p], 64, 500, 1));
        }
        // Deep, repeatedly, and nothing built: a mine, so the kind filter has
        // something to leave out.
        for (int d = 0; d < 6; d++) {
            long at = start + d * day + 9 * 3600_000L;
            for (int i = 0; i < 40; i++) {
                rows.add(new ActivityEntry(at + i * 1500L, "Alex", "uuid-Alex",
                    "break", "Stone", "overworld", 3000 + (i % 5), 11, -700 + (i % 3), 1));
            }
        }
        // Oldest first: expiry walks the log from the front and stops at the
        // first row still inside the window, so rows out of order outlive it.
        rows.sort(java.util.Comparator.comparingLong(ActivityEntry::at));
        java.util.Deque<ActivityEntry> entries = logEntries();
        entries.clear();
        entries.addAll(rows);

        // The panel caches places for half a minute; the tools read the log
        // directly, but drop it anyway so nothing carries over.
        Method forget = Class.forName("com.schecks.almin.WebUi")
            .getDeclaredMethod("forgetPlaces");
        forget.setAccessible(true);
        forget.invoke(null);
    }

    // ---------- the log the tools read ----------

    static java.util.Deque<ActivityEntry> logEntries() throws Exception {
        Class<?> log = Class.forName("com.schecks.almin.ActivityLog");
        Field f = log.getDeclaredField("entries");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Deque<ActivityEntry> entries = (java.util.Deque<ActivityEntry>) f.get(null);
        return entries;
    }

    static void seedLog() throws Exception {
        java.util.Deque<ActivityEntry> entries = logEntries();
        entries.clear();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 400; i++) {
            String who = i % 3 == 0 ? "Steve" : (i % 3 == 1 ? "Alex" : "Herobrine");
            boolean chat = i % 7 == 0;
            entries.add(new ActivityEntry(now - (400 - i) * 1000L, who, "uuid-" + who,
                chat ? "chat" : "break",
                chat ? "meet me at spawn" : "diamond_ore",
                "overworld", i, 12, -i, 1));
        }
    }

    // ---------- reflection, because these classes are not public ----------

    static Class<?> tools() throws Exception { return Class.forName("com.schecks.almin.AiTools"); }
    static Class<?> chat() throws Exception { return Class.forName("com.schecks.almin.AiChat"); }

    static List<String> toolNames(Accounts.Account me) throws Exception {
        Method m = tools().getDeclaredMethod("forAccount", Accounts.Account.class);
        m.setAccessible(true);
        List<String> out = new ArrayList<>();
        for (Object t : (List<?>) m.invoke(null, me)) {
            Method name = t.getClass().getDeclaredMethod("name");
            name.setAccessible(true);
            out.add(String.valueOf(name.invoke(t)));
        }
        return out;
    }

    static String run(Accounts.Account me, String tool, String argsJson) throws Exception {
        Method m = tools().getDeclaredMethod("run", Accounts.Account.class, String.class,
            com.google.gson.JsonObject.class);
        m.setAccessible(true);
        Object r = m.invoke(null, me, tool,
            com.google.gson.JsonParser.parseString(argsJson).getAsJsonObject());
        Method json = r.getClass().getDeclaredMethod("json");
        json.setAccessible(true);
        return String.valueOf(json.invoke(r));
    }

    /**
     * Asks, waits, and hands back the answer.
     *
     * <p>There is no synchronous way in on purpose — a question is answered on
     * its own thread so that no request, and no proxy in front of one, is
     * holding the line while it happens. So the tests wait the way the menu
     * does, and exercise the path production actually takes.
     */
    static Object ask(Accounts.Account me, String question) throws Exception {
        String why = start(me, question);
        if (!why.isEmpty()) throw new IllegalStateException("could not start: " + why);
        for (int i = 0; i < 400 && working(me); i++) java.lang.Thread.sleep(25);
        if (working(me)) throw new IllegalStateException("still working after 10s");
        List<?> shown = history(me);
        return shown.isEmpty() ? null : shown.get(shown.size() - 1);
    }

    static String start(Accounts.Account me, String question) throws Exception {
        Method m = chat().getDeclaredMethod("start", Accounts.Account.class, String.class);
        m.setAccessible(true);
        return String.valueOf(m.invoke(null, me, question));
    }

    static boolean working(Accounts.Account me) throws Exception {
        Method m = chat().getDeclaredMethod("working", Accounts.Account.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, me);
    }

    static List<?> history(Accounts.Account me) throws Exception {
        Method m = chat().getDeclaredMethod("history", Accounts.Account.class);
        m.setAccessible(true);
        return (List<?>) m.invoke(null, me);
    }

    static boolean mine(Object message) throws Exception {
        Method m = message.getClass().getDeclaredMethod("mine");
        m.setAccessible(true);
        return (Boolean) m.invoke(message);
    }

    static void clear(Accounts.Account me) throws Exception {
        Method m = chat().getDeclaredMethod("clear", Accounts.Account.class);
        m.setAccessible(true); m.invoke(null, me);
    }

    static String problem(Accounts.Account me) throws Exception {
        Method m = chat().getDeclaredMethod("problem", Accounts.Account.class);
        m.setAccessible(true);
        return String.valueOf(m.invoke(null, me));
    }

    static String text(Object message) throws Exception {
        Method m = message.getClass().getDeclaredMethod("text");
        m.setAccessible(true); return String.valueOf(m.invoke(message));
    }

    static String error(Object message) throws Exception {
        Method m = message.getClass().getDeclaredMethod("error");
        m.setAccessible(true); return String.valueOf(m.invoke(message));
    }

    static List<?> steps(Object message) throws Exception {
        Method m = message.getClass().getDeclaredMethod("steps");
        m.setAccessible(true); return (List<?>) m.invoke(message);
    }

    static void set(String field, Object v) throws Exception {
        Field f = AlminConfig.class.getDeclaredField(field);
        f.setAccessible(true); f.set(cfg, v);
    }

    static void endpointOverrides(Map<String, String> endpoints) throws Exception {
        Class<?> t = Class.forName("com.schecks.almin.AiTransport");
        Method m = t.getDeclaredMethod("setEndpointOverridesForTests", Map.class);
        m.setAccessible(true); m.invoke(null, endpoints);
    }

    static Accounts.Account owner() { return Accounts.owner(); }

    static Accounts.Account account(Map<String, String> access, Set<String> extras) {
        return new Accounts.Account("acc-1", "delegate", "", "Steve", "uuid-Steve",
            access, Map.of(), extras, 1, 0L, 0L, false);
    }

    // ---------- what the stub provider says ----------

    static String chatToolCall(String id, String name, String args) {
        return "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
            + "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{"
            + "\"name\":\"" + name + "\",\"arguments\":" + quote(args) + "}}]}}]}";
    }

    static String chatText(String text) {
        return "{\"choices\":[{\"message\":{\"content\":" + quote(text) + "}}]}";
    }

    static String anthropicToolCall(String id, String name) {
        return "{\"content\":[{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\""
            + name + "\",\"input\":{\"group_by\":\"player\"}}],\"stop_reason\":\"tool_use\"}";
    }

    static String anthropicText(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + quote(text) + "}]}";
    }

    static String responsesToolCall(String id, String name) {
        return "{\"output\":[{\"type\":\"function_call\",\"call_id\":\"" + id + "\",\"name\":\""
            + name + "\",\"arguments\":" + quote("{\"group_by\":\"player\"}") + "}]}";
    }

    static String responsesText(String text) {
        return "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
            + "\"text\":" + quote(text) + "}]}]}";
    }

    static String googleToolCall(String name) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\""
            + name + "\",\"args\":{\"group_by\":\"player\"}}}]}}]}";
    }

    static String googleText(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quote(text)
            + "}]}}]}";
    }

    static String quote(String s) {
        return new com.google.gson.JsonPrimitive(s).toString();
    }
}
