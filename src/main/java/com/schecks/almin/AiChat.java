package com.schecks.almin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The conversation behind the AI menu.
 *
 * <p>{@link AiInsights} answers one question with one request and everything
 * it might need pasted into the prompt. This does the opposite: the model is
 * sent the question and a list of things it may look up, and it goes and looks
 * them up. A question about one player in one hour costs one small lookup
 * instead of a transcript of the server, and a question that turns out to need
 * three lookups can make three, which no amount of prompt-stuffing achieves.
 *
 * <h3>Rounds, and why there is a ceiling on them</h3>
 * Each round is one request: the model either answers or asks for a lookup,
 * and a lookup means another round. Left alone a model can keep looking things
 * up, and every round is somebody's money and somebody else waiting. So rounds
 * are capped ({@code ai-tool-rounds}). Reaching the cap is not an error — the
 * model is told it has run out and asked to answer with what it has, which
 * produces "here is what I found, I did not get to X" rather than silence.
 *
 * <h3>Threads are per account and per session</h3>
 * A conversation belongs to the account that opened it, is held in memory
 * only, and goes when the server stops. Activity is on disk and can be asked
 * about again; what somebody asked the model about it is not the sort of
 * record that should quietly outlive the asking.
 */
final class AiChat {

    /** One thing the model looked up, kept so the panel can show its working. */
    record Step(String tool, String note) {}

    /** One side of the conversation as the panel draws it. */
    record Message(long at, boolean mine, String text, List<Step> steps, String error) {

        static Message asked(String text) {
            return new Message(System.currentTimeMillis(), true, text, List.of(), "");
        }
        static Message answered(String text, List<Step> steps) {
            return new Message(System.currentTimeMillis(), false, text, steps, "");
        }
        static Message failed(String why, List<Step> steps) {
            return new Message(System.currentTimeMillis(), false, "", steps, why);
        }
    }

    /** Everything one account has asked, and the transcript the model sees. */
    private static final class Thread {
        final List<Message> shown = new ArrayList<>();
        final List<AiTransport.Turn> turns = new ArrayList<>();
        long touched = System.currentTimeMillis();
    }

    /**
     * How much of the conversation is replayed to the model. Older turns fall
     * off the front: a thread that ran all day would otherwise become exactly
     * the giant prompt this whole class exists to avoid.
     */
    private static final int KEPT_TURNS = 40;

    /** Threads held at once, oldest dropped. A panel with many accounts is fine. */
    private static final int MAX_THREADS = 24;

    /** Messages one thread shows. Beyond this the top is trimmed. */
    private static final int MAX_SHOWN = 60;

    /** What one question may be. Long enough to paste a log line into. */
    static final int MAX_QUESTION = 2000;

    private static final Map<String, Thread> threads = new ConcurrentHashMap<>();

    /** One at a time, for the same reason {@link AiInsights} has one: buttons. */
    private static final AtomicBoolean running = new AtomicBoolean(false);

    /** Whose question is being worked on, or "" when none is. */
    private static volatile String workingFor = "";

    /**
     * Where a question is actually answered.
     *
     * <p>Not on the request's thread. The panel has four of those and polls
     * itself every few seconds, and one question can be several round trips to
     * a model that is each allowed {@code ai-timeout-seconds} — minutes, in
     * total, on a thread the rest of the panel wants. Worse, a reverse proxy
     * in front of Almin gives up long before that and returns its own 504,
     * which is the exact failure {@code ai-timeout-seconds} was added to stop
     * happening to a single request. So the request starts the work and comes
     * straight back, and the menu asks again until there is an answer.
     *
     * <p>One thread, because {@link #running} already allows one question at a
     * time; a pool would only ever have work for one of them.
     */
    private static final java.util.concurrent.ExecutorService worker =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            java.lang.Thread t = new java.lang.Thread(r, "almin-ai-chat");
            t.setDaemon(true);
            return t;
        });

    private AiChat() {}

    // ---------- what the panel calls ----------

    /** The conversation so far, for drawing the menu. Never null. */
    static List<Message> history(Accounts.Account me) {
        Thread t = threads.get(keyFor(me));
        return t == null ? List.of() : List.copyOf(t.shown);
    }

    /** Whether this account has a question being worked on right now. */
    static boolean working(Accounts.Account me) {
        return !workingFor.isEmpty() && workingFor.equals(keyFor(me));
    }

    /**
     * Puts a question in the transcript and answers it in the background.
     *
     * <p>Returns what is wrong, or "" if the work started. The menu polls
     * {@link #history} until {@link #working} goes false, which is what keeps
     * a long conversation out of reach of the panel's own request timeout and
     * of any proxy's.
     */
    static String start(Accounts.Account me, String question) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) return "Ask a question first.";
        if (q.length() > MAX_QUESTION) q = q.substring(0, MAX_QUESTION);

        String why = problem(me);
        if (!why.isEmpty()) return why;
        if (!running.compareAndSet(false, true)) {
            return "Already working on a question \u2014 try again in a moment.";
        }
        workingFor = keyFor(me);
        // Added here rather than on the worker so that the very next poll sees
        // it, whichever of the two runs first.
        Thread thread = thread(me);
        thread.shown.add(Message.asked(q));
        trim(thread);

        final String asked = q;
        try {
            worker.execute(() -> {
                try { converse(me, asked); }
                catch (RuntimeException e) {
                    AlminLog.warn("[almin] ai chat: {}", e.toString());
                    thread.shown.add(Message.failed(
                        "Something went wrong answering that: " + e, List.of()));
                } finally {
                    workingFor = "";
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            // The queue refused it, which on a shutting-down executor is the
            // only way here. Undo the latch rather than leave the menu stuck.
            thread.shown.remove(thread.shown.size() - 1);
            workingFor = "";
            running.set(false);
            return "The panel is shutting down.";
        }
        return "";
    }

    /** Forgets one account's conversation. */
    static void clear(Accounts.Account me) {
        threads.remove(keyFor(me));
    }

    /**
     * Whatever stands between this account and an answer, or "" if nothing
     * does. Asked before the question is sent so the menu can say what to fix
     * rather than spend a request finding out.
     */
    static String problem(Accounts.Account me) {
        if (me == null) return "Sign in first.";
        if (me.modelBarred()) {
            return "This account is not allowed to ask the model.";
        }
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.aiChat) {
            return "The AI menu is switched off. Turn on ai-chat in Settings.";
        }
        // Asked before AiInsights.problem(), which words this one as
        // "summaries are off" — true of the feature that switch was written
        // for, and confusing under a menu nobody came to for a summary.
        if (!cfg.aiEnabled) {
            return "No model is switched on. Settings has it, under \"Reading the log "
                + "with a model\" — fill it in and press Turn on.";
        }
        String why = AiInsights.problem();
        return why == null ? "" : why;
    }

    // ---------- the loop ----------

    private static Message converse(Accounts.Account me, String question) {
        Thread thread = thread(me);
        List<AiTools.Tool> tools = AiTools.forAccount(me);
        List<Step> steps = new ArrayList<>();

        // The question may already be on the transcript: start() puts it there
        // before handing the work over, so that a poll arriving first still
        // sees what was asked.
        if (thread.shown.isEmpty() || !thread.shown.get(thread.shown.size() - 1).mine()) {
            thread.shown.add(Message.asked(question));
        }
        thread.turns.add(AiTransport.Turn.user(question));
        trim(thread);

        AlminConfig cfg = AlminConfig.get();
        String provider = AiInsights.provider(cfg);
        int rounds = Math.max(1, cfg.aiToolRounds);

        try {
            for (int round = 1; round <= rounds; round++) {
                boolean last = round == rounds;
                AiTransport.Answer answer = AiTransport.converse(
                    cfg, provider, system(me, tools, last), replay(thread), tools);

                if (!answer.wantsTools()) {
                    return finish(thread, Message.answered(answer.text(), steps),
                        AiTransport.Turn.assistant(answer.text(), List.of()));
                }

                thread.turns.add(AiTransport.Turn.assistant(answer.text(), answer.calls()));
                List<AiTransport.Outcome> results = new ArrayList<>();
                for (AiTransport.Call c : answer.calls()) {
                    AiTools.Result r = AiTools.run(me, c.name(), c.args());
                    steps.add(new Step(c.name(), r.note()));
                    results.add(new AiTransport.Outcome(c.id(), c.name(), r.json()));
                    AlminLog.info("[almin] ai looked up {}: {}", c.name(), r.note());
                }
                thread.turns.add(AiTransport.Turn.tool(results));
                trim(thread);
            }

            // Out of rounds with the model still asking. One more request with
            // no tools at all, so the only thing left to do is answer.
            AiTransport.Answer forced = AiTransport.converse(cfg, provider,
                outOfRounds(rounds), replay(thread), List.of());
            if (forced.text().isBlank()) {
                // Offered no tools and it still did not answer. An empty bubble
                // would read as a bug in the panel rather than as what it is,
                // so this says what happened and what to do about it.
                rewind(thread);
                Message stuck = Message.failed("The model used all " + rounds
                    + " of its lookups without answering. Ask something narrower, or raise "
                    + "ai-tool-rounds in Settings.", steps);
                thread.shown.add(stuck);
                return stuck;
            }
            return finish(thread, Message.answered(forced.text(), steps),
                AiTransport.Turn.assistant(forced.text(), List.of()));

        } catch (IOException e) {
            String said = e.getMessage() == null ? e.toString() : e.getMessage();
            AlminLog.warn("[almin] ai chat failed: {}", said);
            // The failed exchange is dropped from the transcript rather than
            // kept. A half-finished round — an assistant turn whose tool
            // results never arrived — is rejected by every provider, so
            // leaving it in would break the next question too.
            rewind(thread);
            Message failed = Message.failed(said, steps);
            thread.shown.add(failed);
            return failed;
        }
    }

    private static Message finish(Thread thread, Message shown, AiTransport.Turn turn) {
        thread.turns.add(turn);
        thread.shown.add(shown);
        trim(thread);
        return shown;
    }

    /** Drops back to the last complete exchange after a failure mid-round. */
    private static void rewind(Thread thread) {
        for (int i = thread.turns.size() - 1; i >= 0; i--) {
            String role = thread.turns.get(i).role();
            if (role.equals("assistant") || role.equals("tool")) thread.turns.remove(i);
            else break;
        }
    }

    private static List<AiTransport.Turn> replay(Thread thread) {
        List<AiTransport.Turn> all = thread.turns;
        if (all.size() <= KEPT_TURNS) return List.copyOf(all);
        // Cut back to a user turn. Starting a transcript on a tool result, or
        // on an assistant turn whose call is no longer in it, is rejected.
        int from = all.size() - KEPT_TURNS;
        while (from < all.size() && !all.get(from).role().equals("user")) from++;
        if (from >= all.size()) return List.of(all.get(all.size() - 1));
        return List.copyOf(all.subList(from, all.size()));
    }

    private static void trim(Thread thread) {
        thread.touched = System.currentTimeMillis();
        while (thread.shown.size() > MAX_SHOWN) thread.shown.remove(0);
    }

    private static Thread thread(Accounts.Account me) {
        String key = keyFor(me);
        Thread t = threads.computeIfAbsent(key, k -> new Thread());
        if (threads.size() > MAX_THREADS) {
            String oldest = null;
            long when = Long.MAX_VALUE;
            for (Map.Entry<String, Thread> e : threads.entrySet()) {
                if (e.getKey().equals(key)) continue;
                if (e.getValue().touched < when) { when = e.getValue().touched; oldest = e.getKey(); }
            }
            if (oldest != null) threads.remove(oldest);
        }
        return t;
    }

    private static String keyFor(Accounts.Account me) {
        if (me == null) return "?";
        // The id, not the username: renaming an account should not hand its
        // conversation to whoever takes the old name.
        return me.owner() ? "owner" : String.valueOf(me.id());
    }

    /** Forgets everything. Used when the server stops and by the tests. */
    static void forget() {
        threads.clear();
        workingFor = "";
        running.set(false);
    }

    // ---------- what the model is told ----------

    private static String system(Accounts.Account me, List<AiTools.Tool> tools, boolean last) {
        StringBuilder s = new StringBuilder();
        s.append("You are the admin assistant built into Almin, a server-administration mod ")
         .append("for a Minecraft server. You are answering the server's own administrator ")
         .append("in Almin's web panel.\n\n");

        s.append("The current time is ").append(AiTools.stamp(System.currentTimeMillis()))
         .append(". Every timestamp you are given comes with an \"ago\" alongside it; use ")
         .append("those rather than doing date arithmetic yourself.\n\n");

        if (tools.isEmpty()) {
            s.append("You have no way to look anything up: this account cannot read any of the ")
             .append("menus the data comes from. Say so plainly instead of guessing.\n\n");
        } else {
            s.append("You cannot see the server directly. Everything you know about it comes ")
             .append("from the tools you have been given, so look things up rather than ")
             .append("guessing, and never invent a player, a number, a time or an event. ")
             .append("If a lookup returns nothing, the answer is that there is no record of ")
             .append("it — which is a real answer and often the right one.\n\n");

            s.append("Look things up in the cheapest order. count_activity answers ")
             .append("\"who most\", \"what most\" and \"when busiest\" over the whole log in ")
             .append("one call; search_activity is for reading the actual rows once you know ")
             .append("which ones you want. Narrow with player, action and time rather than ")
             .append("paging through everything. activity_overview tells you how far back the ")
             .append("log goes, which is worth knowing before answering a question about last ")
             .append("week.\n\n");

            s.append("The log is kept on disk and survives restarts, so it is not limited to ")
             .append("the session running now — but it does not go back forever. If a question ")
             .append("reaches past the oldest row, say that the log does not cover it rather ")
             .append("than answering from the part that survives.\n\n");
        }

        s.append("Some things are deliberately not recorded or not shown to this account, and ")
         .append("what you can see is already narrowed to what they may see. If something is ")
         .append("missing, report it as missing. Do not speculate about what the hidden ")
         .append("content might have been.\n\n");

        s.append("Answer in plain prose, briefly, the way a colleague would. Give numbers and ")
         .append("names when you have them. Do not describe the lookups you made unless they ")
         .append("change what the answer means — the panel already shows them.");

        if (last) {
            s.append("\n\nThis is your last chance to look anything up. Make it count, then ")
             .append("answer with what you have.");
        }
        return s.toString();
    }

    private static String outOfRounds(int rounds) {
        return "You are the admin assistant built into Almin, answering a Minecraft server's "
            + "administrator. You have used all " + rounds + " of your lookups for this "
            + "question and cannot make any more. Answer now with what you already found. "
            + "Say plainly which part of the question you did not get to, and suggest a "
            + "narrower question that would reach it.";
    }
}
