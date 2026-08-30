import com.schecks.almin.AiInsights;
import com.schecks.almin.Episodes;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Does it actually send the request?
 *
 * Everything else about this feature has been tested without a network: the
 * prompt, the parsing, the key file. None of that would notice a request that
 * never leaves. So this stands up a real HTTP server on loopback, points the
 * provider at it, and asks what arrived.
 */
public class AiWireTests {
    static int failures = 0;
    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        AtomicReference<String> body = new AtomicReference<>("");
        AtomicReference<String> path = new AtomicReference<>("");
        AtomicReference<String> auth = new AtomicReference<>("");
        AtomicReference<String> anthropicKey = new AtomicReference<>("");
        AtomicReference<String> googleKey = new AtomicReference<>("");
        List<String> hits = new ArrayList<>();

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            hits.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            path.set(ex.getRequestURI().getPath());
            auth.set(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            anthropicKey.set(String.valueOf(ex.getRequestHeaders().getFirst("x-api-key")));
            googleKey.set(String.valueOf(ex.getRequestHeaders().getFirst("x-goog-api-key")));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String answer = "{\"summary\":\"A quiet night.\",\"moments\":[]}";
            String p = ex.getRequestURI().getPath();
            String json = p.equals("/openai/responses")
                ? "{\"status\":\"completed\",\"output\":[{\"type\":\"message\","
                    + "\"content\":[{\"type\":\"output_text\",\"text\":"
                    + com.google.gson.JsonParser.parseString('"' + answer.replace("\"", "\\\"") + '"')
                    + "}]}]}"
                : p.equals("/anthropic/messages")
                    ? "{\"content\":[{\"type\":\"text\",\"text\":"
                        + com.google.gson.JsonParser.parseString('"' + answer.replace("\"", "\\\"") + '"')
                        + "}]}"
                    : p.equals("/google/generate")
                        ? "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                            + com.google.gson.JsonParser.parseString('"' + answer.replace("\"", "\\\"") + '"')
                            + "}]}}]}"
                        : "{\"choices\":[{\"message\":{\"content\":"
                            + com.google.gson.JsonParser.parseString('"' + answer.replace("\"", "\\\"") + '"')
                            + "}}]}";
            byte[] reply = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, reply.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(reply); }
            ex.close();
        });
        http.start();
        int port = http.getAddress().getPort();
        String base = "http://127.0.0.1:" + port + "/v1";
        setEndpointOverrides(Map.of(
            "openai", "http://127.0.0.1:" + port + "/openai/responses",
            "anthropic", "http://127.0.0.1:" + port + "/anthropic/messages",
            "google", "http://127.0.0.1:" + port + "/google/generate"));

        Path dir = Files.createTempDirectory("almin-aiwire");
        AiInsights.init(dir);
        configure(true, "local", "qwen2.5:3b", base);

        check("a configured local model reports no problem: " + AiInsights.problem(),
            AiInsights.problem().isEmpty());

        AiInsights.Report r = AiInsights.summarise(AiInsights.Scope.all(), episodes(),
            List.of(), System.currentTimeMillis() - 60000, System.currentTimeMillis(), 2, true);

        check("the request actually went out", !hits.isEmpty());
        if (hits.isEmpty()) {
            System.out.println("       error was: " + r.error());
        }
        check("it went to the chat endpoint (" + path.get() + ")",
            path.get().equals("/v1/chat/completions"));
        check("it carried the model", body.get().contains("qwen2.5:3b"));
        check("it carried the episodes", body.get().contains("Dug a shaft"));
        check("no bearer header for a local model", auth.get().equals("null"));
        check("the answer came back", r.ok() && r.summary().equals("A quiet night."));

        List<com.schecks.almin.ActivityLog.Entry> sceneRows = sceneRows();
        List<Episodes.Episode> sceneEpisodes = Episodes.of(sceneRows);
        AiInsights.Report pictured = AiInsights.summarise(AiInsights.Scope.all(), sceneEpisodes,
            sceneRows, NOW - 60_000, NOW, 2, true);
        check("OpenAI-compatible models receive the optional scene diagram",
            pictured.ok() && body.get().contains("\"type\":\"image_url\"")
                && body.get().contains("data:image/png;base64,"));

        // The provider the panel calls "somewhere else". It takes the same
        // road as `local` and the only way to know that is to watch it arrive.
        hits.clear(); path.set(""); auth.set("");
        configure(true, "custom", "nemotron-3.5-lightning-30b-a3b", base);
        check("a custom endpoint reports no problem: " + AiInsights.problem(),
            AiInsights.problem().isEmpty());
        AiInsights.Report cu = AiInsights.summarise(AiInsights.Scope.all(), episodes(),
            List.of(), System.currentTimeMillis() - 60000, System.currentTimeMillis(), 2, true);
        check("a custom endpoint is actually contacted", !hits.isEmpty());
        if (hits.isEmpty()) System.out.println("       error was: " + cu.error());
        check("it went to the chat endpoint (" + path.get() + ")",
            path.get().equals("/v1/chat/completions"));
        check("it carried the model name as typed",
            body.get().contains("nemotron-3.5-lightning-30b-a3b"));
        hits.clear();

        // Hosted providers each have their own request and response contract.
        AiInsights.setKey("wire-secret");
        configure(true, "openai", "gpt-test", "");
        AiInsights.Report oa = AiInsights.summarise(AiInsights.Scope.all(), sceneEpisodes,
            sceneRows, NOW - 60_000, NOW, 0, true);
        check("OpenAI uses the Responses endpoint", path.get().equals("/openai/responses"));
        check("OpenAI sends instructions, input and the Responses token limit",
            body.get().contains("\"instructions\"") && body.get().contains("\"input\"")
            && body.get().contains("\"max_output_tokens\":2000")
            && body.get().contains("\"store\":false"));
        check("OpenAI Responses receives an input_image part",
            body.get().contains("\"type\":\"input_image\"")
                && body.get().contains("data:image/png;base64,"));
        check("OpenAI sends its bearer key", auth.get().equals("Bearer wire-secret"));
        check("OpenAI's response comes back", oa.ok() && oa.summary().equals("A quiet night."));

        configure(true, "anthropic", "claude-test", "");
        AiInsights.Report an = AiInsights.summarise(AiInsights.Scope.all(), sceneEpisodes,
            sceneRows, NOW - 60_000, NOW, 0, true);
        check("Anthropic uses Messages", path.get().equals("/anthropic/messages")
            && body.get().contains("\"system\"") && body.get().contains("\"messages\""));
        check("Anthropic receives a base64 image content block",
            body.get().contains("\"type\":\"image\"")
                && body.get().contains("\"media_type\":\"image/png\""));
        check("Anthropic sends x-api-key", anthropicKey.get().equals("wire-secret"));
        check("Anthropic's response comes back", an.ok() && an.summary().equals("A quiet night."));

        configure(true, "google", "gemini-test", "");
        AiInsights.Report go = AiInsights.summarise(AiInsights.Scope.all(), sceneEpisodes,
            sceneRows, NOW - 60_000, NOW, 0, true);
        check("Gemini uses generateContent", path.get().equals("/google/generate")
            && body.get().contains("\"systemInstruction\"")
            && body.get().contains("\"generationConfig\""));
        check("Gemini receives inline PNG data",
            body.get().contains("\"inlineData\"")
                && body.get().contains("\"mimeType\":\"image/png\""));
        check("Gemini sends x-goog-api-key", googleKey.get().equals("wire-secret"));
        check("Gemini's response comes back", go.ok() && go.summary().equals("A quiet night."));
        String diagnostic = diagnosticsText();
        check("diagnostics retain the raw request and response",
            diagnostic.contains("systemInstruction") && diagnostic.contains("A quiet night"));
        check("diagnostics never retain credential values", !diagnostic.contains("wire-secret"));
        AiInsights.setKey("");

        // A base url with a trailing slash must not become //chat/completions.
        hits.clear();
        configure(true, "local", "m", base + "/");
        AiInsights.summarise(AiInsights.Scope.all(), episodes(), List.of(), 0, 1, 0, true);
        check("a trailing slash on the base url is handled",
            path.get().equals("/v1/chat/completions"));

        // With a key, it should be sent.
        hits.clear();
        AiInsights.setKey("sk-test-value");
        configure(true, "local", "m", base);
        AiInsights.summarise(AiInsights.Scope.all(), episodes(), List.of(), 0, 1, 0, true);
        check("a key is sent when there is one", auth.get().equals("Bearer sk-test-value"));
        AiInsights.setKey("");

        // The cooldown must not swallow a deliberate press.
        hits.clear();
        AiInsights.summarise(AiInsights.Scope.all(), episodes(), List.of(), 0, 1, 0, true);
        check("force beats the cooldown", !hits.isEmpty());

        // ...but an unforced call inside it should reuse the answer.
        hits.clear();
        AiInsights.summarise(AiInsights.Scope.all(), episodes(), List.of(), 0, 1, 0, false);
        check("an unforced call inside the cooldown does not ask again", hits.isEmpty());

        // Some small local OpenAI-compatible models are text-only. One clear
        // vision rejection should retry without the image and be remembered.
        AtomicInteger visionAttempts = new AtomicInteger();
        AtomicInteger imagesRejected = new AtomicInteger();
        http.createContext("/visionless/v1/chat/completions", ex -> {
            visionAttempts.incrementAndGet();
            String request = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply;
            int status;
            if (request.contains("image_url")) {
                imagesRejected.incrementAndGet(); status = 400;
                reply = "{\"error\":{\"message\":\"image input is not supported by this model\"}}";
            } else {
                status = 200;
                reply = "{\"choices\":[{\"message\":{\"content\":\"{\\\"summary\\\":\\\"text fallback\\\"}\"}}]}";
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
            ex.close();
        });
        configure(true, "local", "text-only-model",
            "http://127.0.0.1:" + port + "/visionless/v1");
        AiInsights.Report fallback = AiInsights.summarise(AiInsights.Scope.all(), sceneEpisodes,
            sceneRows, NOW - 60_000, NOW, 0, true);
        check("a text-only model falls back cleanly after rejecting the diagram",
            fallback.ok() && fallback.summary().equals("text fallback")
                && visionAttempts.get() == 2 && imagesRejected.get() == 1);
        AiInsights.summarise(AiInsights.Scope.all(), sceneEpisodes,
            sceneRows, NOW - 60_000, NOW, 0, true);
        check("the text-only capability is remembered", visionAttempts.get() == 3
            && imagesRejected.get() == 1);

        // A provider that errors must say so rather than looking like success.
        http.createContext("/bad", ex -> {
            byte[] reply = "{\"error\":{\"message\":\"model not found\"}}"
                .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, reply.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(reply); }
            ex.close();
        });
        configure(true, "local", "m", "http://127.0.0.1:" + port + "/bad");
        AiInsights.Report bad = AiInsights.summarise(AiInsights.Scope.all(), episodes(),
            List.of(), 0, 1, 0, true);
        check("a 404 comes back as a message, not a summary",
            !bad.ok() && bad.error().toLowerCase().contains("model"));

        // Nothing at the other end at all.
        configure(true, "local", "m", "http://127.0.0.1:1/v1");
        AiInsights.Report dead = AiInsights.summarise(AiInsights.Scope.all(), episodes(),
            List.of(), 0, 1, 0, true);
        check("a refused connection is a message too", !dead.ok() && !dead.error().isEmpty());
        System.out.println("       (" + dead.error() + ")");

        // ---- what a stretch was for ----
        hits.clear();
        configure(true, "local", "m", base);
        // A reply that carries per-episode readings, anchored by timestamp.
        long endsAt = episodes().get(0).to();
        http.removeContext("/");
        http.createContext("/", ex -> {
            hits.add("post");
            ex.getRequestBody().readAllBytes();
            byte[] reply = ("{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"summary\\\":\\\"s\\\",\\\"moments\\\":[],"
                + "\\\"sequences\\\":[{\\\"at\\\":" + endsAt
                + ",\\\"means\\\":\\\"Getting to the ore layer.\\\"},"
                + "{\\\"at\\\":1,\\\"means\\\":\\\"Invented.\\\"}]}\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, reply.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(reply); }
            ex.close();
        });
        AiInsights.Report meant = AiInsights.summarise(AiInsights.Scope.all(), episodes(),
            List.of(), 0, 1, 0, true);
        check("a reading is kept", meant.meanings().size() == 1);
        check("...anchored to the episode it names",
            meant.meanings().get(0).at() == endsAt
            && meant.meanings().get(0).player().equals("Steve"));
        check("...and one anchored to nothing is dropped",
            meant.meanings().stream().noneMatch(m -> m.means().equals("Invented.")));

        // The prompt has to ask for them, or nothing above matters.
        java.lang.reflect.Field sys = AiInsights.class.getDeclaredField("SYSTEM");
        sys.setAccessible(true);
        String prompt = (String) sys.get(null);
        check("the prompt asks what a stretch was for",
            prompt.contains("sequences") && prompt.contains("what it was probably FOR"));
        check("...and tells it not to invent a reason",
            prompt.contains("leave it out rather than inventing"));

        // ---- the cache is per subject ----
        hits.clear();
        // The same unforced call, but about one player rather than everything.
        // A single cached slot would have handed back the answer above.
        AiInsights.summarise(AiInsights.Scope.of("Steve"), episodes(), List.of(),
            0, 1, 0, false);
        check("a different subject is a different question", !hits.isEmpty());
        hits.clear();
        AiInsights.summarise(AiInsights.Scope.of("Steve"), episodes(), List.of(),
            0, 1, 0, false);
        check("...and the same one is not asked twice", hits.isEmpty());

        // An area asks only about what is in it: an episode 10,30,20 is inside
        // a 64-block box at 0,0 and outside one at 4000,4000.
        hits.clear();
        AtomicReference<String> scoped = new AtomicReference<>("");
        http.removeContext("/");
        http.createContext("/", ex -> {
            hits.add("post");
            scoped.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = ("{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"summary\\\":\\\"ok\\\"}\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, reply.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(reply); }
            ex.close();
        });
        AiInsights.summarise(AiInsights.Scope.area("overworld", 0, 0, 64), episodes(),
            List.of(), 0, 1, 0, true);
        check("an area prompt carries what is in it", scoped.get().contains("Dug a shaft"));
        AiInsights.summarise(AiInsights.Scope.area("overworld", 4000, 4000, 64), episodes(),
            List.of(), 0, 1, 0, true);
        check("...and leaves out what is not",
            !scoped.get().contains("Dug a shaft") && scoped.get().contains("nothing recorded"));

        // ---- what am I looking for ----
        hits.clear();
        AtomicReference<String> lensBody = new AtomicReference<>("");
        long shaftAt = episodes().get(0).to();
        http.removeContext("/");
        http.createContext("/", ex -> {
            hits.add("post");
            lensBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = ("{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"reply\\\":\\\"Digging by Steve.\\\","
                + "\\\"players\\\":[\\\"Steve\\\"],"
                + "\\\"actions\\\":[\\\"break\\\"],"
                + "\\\"items\\\":[\\\"Stone\\\"],"
                + "\\\"kinds\\\":[\\\"shaft\\\"],"
                + "\\\"episodes\\\":[" + shaftAt + ",99]}\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, reply.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(reply); }
            ex.close();
        });
        AiInsights.Lens lens = AiInsights.look("show me digging near spawn", episodes(),
            List.of(row("Steve", "break", "Stone")), 0, 1);
        check("a question goes out", !hits.isEmpty());
        check("it is told what words are actually in the log",
            lensBody.get().contains("Action names in the log") && lensBody.get().contains("break"));
        check("the answer comes back as a filter",
            lens.ok() && lens.actions().contains("break") && lens.players().contains("Steve"));
        check("an episode it was given is kept", lens.episodes().contains(shaftAt));
        check("...and one it invented is not", !lens.episodes().contains(99L));
        check("an empty question never leaves the machine",
            !AiInsights.look("  ", episodes(), List.of(), 0, 1).ok());

        // ---- a second opinion on a mod list ----
        hits.clear();
        AtomicReference<String> modBody = new AtomicReference<>("");
        http.removeContext("/");
        http.createContext("/", ex -> {
            hits.add("post");
            modBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = ("{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"summary\\\":\\\"Mostly graphics.\\\","
                + "\\\"flags\\\":[{\\\"id\\\":\\\"xray\\\","
                + "\\\"level\\\":\\\"concern\\\","
                + "\\\"why\\\":\\\"Sees ores.\\\"},"
                + "{\\\"id\\\":\\\"weird\\\",\\\"level\\\":\\\"nonsense\\\"}]}\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, reply.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(reply); }
            ex.close();
        });
        long day = System.currentTimeMillis() - 86_400_000L;
        AiInsights.ModReview mods = AiInsights.review("Steve",
            List.of(new com.schecks.almin.ClientProfiles.Mod("sodium", "0.6", day, 0),
                    new com.schecks.almin.ClientProfiles.Mod("xray", "1.0", day, 0)),
            List.of(new com.schecks.almin.ClientProfiles.Mod("iris", "1.8", day, day)));
        check("a mod list goes out", !hits.isEmpty());
        check("it carries what is installed and what went",
            modBody.get().contains("sodium") && modBody.get().contains("Removed recently"));
        check("a flag comes back", mods.ok() && mods.flags().size() == 2);
        check("...with a level that means something",
            mods.flags().get(0).level().equals("concern")
            && mods.flags().get(1).level().equals("fine"));
        check("an empty mod list is never asked about",
            !AiInsights.review("Steve", List.of(), List.of()).ok());

        // The prompt has to hold it back, or the flags are worthless.
        java.lang.reflect.Field msys = AiInsights.class.getDeclaredField("MODS_SYSTEM");
        msys.setAccessible(true);
        String modPrompt = (String) msys.get(null);
        check("the mod prompt says most of a list is ordinary",
            modPrompt.contains("Most of a long list is"));
        check("...and that none of it is proof",
            modPrompt.contains("nothing here is proof") && modPrompt.contains("Do not accuse"));
        check("...and to say so rather than guess at an unknown id",
            modPrompt.contains("rather than guessing at it"));

        // And the summary prompt has to ask for the patterns.
        check("the prompt asks for patterns the rules missed",
            prompt.contains("patterns the episodes do not name")
            && prompt.contains("quarter-hour table"));
        check("...and to send nothing rather than invent one",
            prompt.contains("a made-up pattern is worse than none"));

        http.stop(0);
        setEndpointOverrides(Map.of());
        System.out.println(failures == 0 ? "AI WIRE OK" : "AI WIRE FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }

    /** Fixed, so a timestamp taken from it still matches the next call. */
    private static final long NOW = System.currentTimeMillis();
    private static final List<Episodes.Episode> EPISODES =
        List.of(new Episodes.Episode("shaft", "Dug a shaft from y 64 down to y 11",
            "Steve", "u", "overworld", NOW - 60000, NOW - 10000, 10, 30, 20, 4, 50, 40, 44,
            "pickaxe"));

    static List<Episodes.Episode> episodes() { return EPISODES; }

    static com.schecks.almin.ActivityLog.Entry row(String who, String action, String detail) {
        return new com.schecks.almin.ActivityLog.Entry(NOW - 30_000, who, "u", action, detail,
            "overworld", 10, 30, 20, 1);
    }

    static List<com.schecks.almin.ActivityLog.Entry> sceneRows() {
        List<com.schecks.almin.ActivityLog.Entry> out = new ArrayList<>();
        for (int x = 0; x < 4; x++) for (int y = 0; y < 2; y++) {
            out.add(new com.schecks.almin.ActivityLog.Entry(NOW - 30_000 + x * 10 + y,
                "Steve", "u", "place", "Oak Planks", "overworld",
                10 + x, 64 + y, 20, 1));
        }
        return out;
    }

    static void configure(boolean on, String provider, String model, String base)
            throws Exception {
        Class<?> cfg = Class.forName("com.schecks.almin.AlminConfig");
        Object c = cfg.getMethod("get").invoke(null);
        cfg.getField("aiEnabled").setBoolean(c, on);
        cfg.getField("aiProvider").set(c, provider);
        cfg.getField("aiModel").set(c, model);
        cfg.getField("aiBaseUrl").set(c, base);
        cfg.getField("aiSendChat").setBoolean(c, true);
        cfg.getField("aiSendSceneImages").setBoolean(c, true);
        Field inst = cfg.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, c);
    }

    static void setEndpointOverrides(Map<String, String> endpoints) throws Exception {
        Class<?> transport = Class.forName("com.schecks.almin.AiTransport");
        var method = transport.getDeclaredMethod("setEndpointOverridesForTests", Map.class);
        method.setAccessible(true);
        method.invoke(null, endpoints);
    }

    static String diagnosticsText() throws Exception {
        Class<?> transport = Class.forName("com.schecks.almin.AiTransport");
        var method = transport.getDeclaredMethod("diagnostics");
        method.setAccessible(true);
        return String.valueOf(method.invoke(null));
    }
}
