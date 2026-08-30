package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete outbound AI request layer.
 *
 * <p>Providers do not share an imaginary lowest-common-denominator request.
 * OpenAI uses Responses, Anthropic uses Messages, Gemini uses generateContent,
 * and local/custom services use the OpenAI-compatible Chat Completions shape.
 * Each request is built and decoded here; {@link AiInsights} only supplies the
 * instructions and reads the resulting text.
 */
final class AiTransport {

    /** A redacted, byte-for-byte account of one attempt for the admin panel. */
    record Diagnostic(long at, String provider, String model, String url,
                      List<String> requestHeaders, String requestBody,
                      int status, List<String> responseHeaders, String responseBody,
                      long elapsedMs, String error) {}

    private enum Shape { OPENAI_RESPONSES, OPENAI_CHAT, ANTHROPIC, GOOGLE }

    private record Request(String provider, String model, String url, Shape shape,
                           Map<String, String> headers, byte[] body) {}

    private record Reply(int status, byte[] body, List<String> headerNames, long elapsedMs) {}

    private static final String AGENT = "Almin/" + Almin.MOD_ID;
    private static final int MAX_RESPONSE = 512 * 1024;
    private static final int MAX_DIAGNOSTICS = 8;
    private static final ArrayDeque<Diagnostic> DIAGNOSTICS = new ArrayDeque<>();
    private static final java.util.Set<String> NO_VISION =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Test-only endpoint substitutions, set reflectively by the wire suite.
     * There is deliberately no config or web route for these: an admin must
     * not be able to redirect a hosted-provider key to an arbitrary service.
     */
    private static volatile Map<String, String> endpointOverrides = Map.of();

    private AiTransport() {}

    static String ask(AlminConfig cfg, String system, String prompt) throws IOException {
        return ask(cfg, AiInsights.provider(cfg), system, prompt);
    }

    static String ask(AlminConfig cfg, String provider, String system, String prompt)
            throws IOException {
        return ask(cfg, provider, system, prompt, null);
    }

    /** Sends a PNG when possible, remembering models that explicitly reject it. */
    static String ask(AlminConfig cfg, String provider, String system, String prompt,
                      byte[] scenePng) throws IOException {
        String visionKey = provider + "\u0000" + cfg.aiModel + "\u0000" + cfg.aiBaseUrl;
        String image = scenePng == null || NO_VISION.contains(visionKey) ? null
            : "data:image/png;base64," + Base64.getEncoder().encodeToString(scenePng);
        try {
            return askProvider(cfg, provider, system, prompt, image);
        } catch (IOException e) {
            if (image == null || !visionRejected(e.getMessage())) throw e;
            NO_VISION.add(visionKey);
            AlminLog.info("[almin] {} does not accept image input; retrying with text only",
                cfg.aiModel);
            return askProvider(cfg, provider, system, prompt, null);
        }
    }

    private static String askProvider(AlminConfig cfg, String provider, String system,
                                      String prompt, String image) throws IOException {
        return switch (provider) {
            case "openai" -> askOnce(openAi(cfg, system, prompt, image));
            case "anthropic" -> askOnce(anthropic(cfg, system, prompt, image));
            case "google" -> askOnce(google(cfg, system, prompt, image));
            case "custom", "local" -> openAiCompatible(cfg, provider, system, prompt, image);
            default -> throw new IOException("Unknown AI provider: " + provider);
        };
    }

    private static boolean visionRejected(String message) {
        String s = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        boolean image = s.contains("image") || s.contains("vision")
            || s.contains("multimodal") || s.contains("content part");
        boolean rejected = s.contains("unsupported") || s.contains("not support")
            || s.contains("doesn't support") || s.contains("cannot accept")
            || s.contains("invalid") || s.contains("unknown type");
        return image && rejected;
    }

    static synchronized List<Diagnostic> diagnostics() {
        return List.copyOf(DIAGNOSTICS);
    }

    static void setEndpointOverridesForTests(Map<String, String> endpoints) {
        endpointOverrides = endpoints == null ? Map.of() : Map.copyOf(endpoints);
        NO_VISION.clear();
    }

    static synchronized void clearDiagnosticsForTests() {
        DIAGNOSTICS.clear();
    }

    private static String openAiCompatible(AlminConfig cfg, String provider,
                                            String system, String prompt, String image)
            throws IOException {
        Request first = openAiChat(cfg, provider, system, prompt, image, false);
        try {
            return askOnce(first);
        } catch (IOException e) {
            String said = e.getMessage() == null ? "" : e.getMessage();
            if (!said.contains("max_completion_tokens")) throw e;
            AlminLog.info("[almin] this model wants max_completion_tokens; asking again");
            return askOnce(openAiChat(cfg, provider, system, prompt, image, true));
        }
    }

    /** OpenAI's current Responses API. */
    private static Request openAi(AlminConfig cfg, String system, String prompt, String image) {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.aiModel.trim());
        body.addProperty("instructions", system);
        if (image == null) {
            body.addProperty("input", prompt);
        } else {
            JsonArray content = new JsonArray();
            content.add(textPart("input_text", prompt));
            JsonObject picture = new JsonObject();
            picture.addProperty("type", "input_image");
            picture.addProperty("image_url", image);
            picture.addProperty("detail", "low");
            content.add(picture);
            JsonObject turn = new JsonObject();
            turn.addProperty("role", "user");
            turn.add("content", content);
            JsonArray input = new JsonArray();
            input.add(turn);
            body.add("input", input);
        }
        body.addProperty("max_output_tokens", 2000);
        // These are one-shot summaries. Do not retain player activity as API state.
        body.addProperty("store", false);
        return request("openai", cfg.aiModel,
            endpoint("openai", "https://api.openai.com/v1/responses"),
            Shape.OPENAI_RESPONSES, body,
            Map.of("Authorization", "Bearer " + AiInsights.key()));
    }

    /** OpenAI-compatible Chat Completions for local and custom endpoints. */
    private static Request openAiChat(AlminConfig cfg, String provider, String system,
                                      String prompt, String image, boolean renamedLimit) {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.aiModel.trim());
        body.addProperty(renamedLimit ? "max_completion_tokens" : "max_tokens", 2000);
        body.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        if (image == null) {
            messages.add(message("user", prompt));
        } else {
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            JsonArray content = new JsonArray();
            content.add(textPart("text", prompt));
            JsonObject picture = new JsonObject();
            picture.addProperty("type", "image_url");
            JsonObject url = new JsonObject();
            url.addProperty("url", image);
            url.addProperty("detail", "low");
            picture.add("image_url", url);
            content.add(picture);
            user.add("content", content);
            messages.add(user);
        }
        body.add("messages", messages);

        String base = trimSlashes(cfg.aiBaseUrl);
        Map<String, String> headers = new LinkedHashMap<>();
        String key = AiInsights.key();
        if (!key.isEmpty()) headers.put("Authorization", "Bearer " + key);
        return request(provider, cfg.aiModel, base + "/chat/completions",
            Shape.OPENAI_CHAT, body, headers);
    }

    /** Anthropic's Messages API. */
    private static Request anthropic(AlminConfig cfg, String system, String prompt, String image) {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.aiModel.trim());
        body.addProperty("max_tokens", 2000);
        body.addProperty("system", system);
        JsonArray messages = new JsonArray();
        if (image == null) {
            messages.add(message("user", prompt));
        } else {
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            JsonArray content = new JsonArray();
            content.add(textPart("text", prompt));
            JsonObject picture = new JsonObject();
            picture.addProperty("type", "image");
            JsonObject source = new JsonObject();
            source.addProperty("type", "base64");
            source.addProperty("media_type", "image/png");
            source.addProperty("data", image.substring(image.indexOf(',') + 1));
            picture.add("source", source);
            content.add(picture);
            user.add("content", content);
            messages.add(user);
        }
        body.add("messages", messages);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", AiInsights.key());
        headers.put("anthropic-version", "2023-06-01");
        return request("anthropic", cfg.aiModel,
            endpoint("anthropic", "https://api.anthropic.com/v1/messages"),
            Shape.ANTHROPIC, body, headers);
    }

    /** Google's Gemini generateContent API. */
    private static Request google(AlminConfig cfg, String system, String prompt, String image) {
        JsonObject sys = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysText = new JsonObject();
        sysText.addProperty("text", system);
        sysParts.add(sysText);
        sys.add("parts", sysParts);

        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        if (image != null) {
            JsonObject picture = new JsonObject();
            JsonObject inline = new JsonObject();
            inline.addProperty("mimeType", "image/png");
            inline.addProperty("data", image.substring(image.indexOf(',') + 1));
            picture.add("inlineData", inline);
            parts.add(picture);
        }
        JsonObject turn = new JsonObject();
        turn.addProperty("role", "user");
        turn.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(turn);

        JsonObject body = new JsonObject();
        body.add("systemInstruction", sys);
        body.add("contents", contents);
        JsonObject generation = new JsonObject();
        generation.addProperty("maxOutputTokens", 2000);
        body.add("generationConfig", generation);

        String base = cfg.aiBaseUrl == null || cfg.aiBaseUrl.isBlank()
            ? "https://generativelanguage.googleapis.com/v1beta"
            : cfg.aiBaseUrl.trim();
        String url = trimSlashes(base) + "/models/" + cfg.aiModel.trim() + ":generateContent";
        return request("google", cfg.aiModel, endpoint("google", url), Shape.GOOGLE,
            body, Map.of("x-goog-api-key", AiInsights.key()));
    }

    private static Request request(String provider, String model, String url, Shape shape,
                                   JsonObject body, Map<String, String> headers) {
        return new Request(provider, model == null ? "" : model.trim(), url, shape,
            Map.copyOf(headers), body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static JsonObject textPart(String type, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("text", text);
        return o;
    }

    private static String askOnce(Request request) throws IOException {
        Reply reply = send(request);
        String raw = new String(reply.body(), StandardCharsets.UTF_8);
        try {
            if (reply.status() / 100 != 2) {
                throw new IOException(explain(reply.status(), raw));
            }
            JsonObject json;
            try {
                JsonElement parsed = JsonParser.parseString(raw);
                if (!parsed.isJsonObject()) {
                    throw new IOException("The service did not return a JSON object.");
                }
                json = parsed.getAsJsonObject();
            } catch (com.google.gson.JsonParseException e) {
                throw new IOException("The service sent back something that was not JSON.");
            }
            if (json.has("error") && !json.get("error").isJsonNull()) {
                throw new IOException(errorText(json.get("error"),
                    "The model reported an error."));
            }

            String text = switch (request.shape()) {
                case OPENAI_RESPONSES -> readOpenAi(json);
                case OPENAI_CHAT -> readOpenAiChat(json);
                case ANTHROPIC -> readAnthropic(json);
                case GOOGLE -> readGoogle(json);
            };
            if (text.isBlank()) throw new IOException(noTextReason(request.shape(), json));
            return text;
        } catch (IOException e) {
            markLastError(request, e.getMessage());
            throw e;
        }
    }

    private static Reply send(Request r) throws IOException {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(r.url()));
        } catch (IllegalArgumentException e) {
            throw new IOException("The model address is not a valid URL: " + r.url());
        }
        builder.header("User-Agent", AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(AiInsights.timeout())
            .POST(HttpRequest.BodyPublishers.ofByteArray(r.body()));
        for (Map.Entry<String, String> h : r.headers().entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
        HttpRequest request = builder.build();
        List<String> requestNames = new ArrayList<>();
        request.headers().map().keySet().forEach(requestNames::add);
        requestNames.sort(String.CASE_INSENSITIVE_ORDER);

        AlminLog.info("[almin] asking {} at {} ({} bytes)", r.provider(), r.url(), r.body().length);
        long began = System.currentTimeMillis();
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpResponse<byte[]> response = client.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
            long elapsed = System.currentTimeMillis() - began;
            byte[] bytes = response.body() == null ? new byte[0] : response.body();
            List<String> responseNames = new ArrayList<>(response.headers().map().keySet());
            responseNames.sort(String.CASE_INSENSITIVE_ORDER);
            if (bytes.length > MAX_RESPONSE) {
                String why = "The model's answer was too large (" + bytes.length + " bytes).";
                remember(new Diagnostic(System.currentTimeMillis(), r.provider(), r.model(),
                    r.url(), List.copyOf(requestNames),
                    new String(r.body(), StandardCharsets.UTF_8), response.statusCode(),
                    List.copyOf(responseNames), "(body not retained: over " + MAX_RESPONSE
                        + " bytes)", elapsed, why));
                throw new ResponseTooLarge(why);
            }
            String raw = new String(bytes, StandardCharsets.UTF_8);
            String error = response.statusCode() / 100 == 2
                ? "" : explain(response.statusCode(), raw);
            remember(new Diagnostic(System.currentTimeMillis(), r.provider(), r.model(), r.url(),
                List.copyOf(requestNames), new String(r.body(), StandardCharsets.UTF_8),
                response.statusCode(), List.copyOf(responseNames), raw, elapsed, error));
            AlminLog.info("[almin] {} answered {} after {} ms",
                r.provider(), response.statusCode(), elapsed);
            return new Reply(response.statusCode(), bytes, responseNames, elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rememberFailure(r, requestNames, began, "Interrupted while waiting for the model.");
            throw new IOException("Interrupted while waiting for the model.");
        } catch (java.net.ConnectException e) {
            String why = "Nothing answered at " + hostOf(r.url())
                + ". Is the model running, and is the address right?";
            rememberFailure(r, requestNames, began, why);
            AlminLog.warn("[almin] could not reach {}: {}", r.url(), e.getMessage());
            throw new IOException(why);
        } catch (java.net.http.HttpTimeoutException e) {
            String why = "Connected to " + hostOf(r.url()) + ", then nothing came back in "
                + AiInsights.timeout().toSeconds() + " seconds. Something is listening there, "
                + "but it did not answer an HTTP request — try https:// if that port uses TLS, "
                + "and check the model server's own log for the request.";
            rememberFailure(r, requestNames, began, why);
            AlminLog.warn("[almin] {} timed out after {}s", r.url(), AiInsights.timeout().toSeconds());
            throw new IOException(why);
        } catch (java.net.UnknownHostException e) {
            String why = "No such host: " + hostOf(r.url());
            rememberFailure(r, requestNames, began, why);
            throw new IOException(why);
        } catch (ResponseTooLarge e) {
            throw e;
        } catch (IOException e) {
            String why = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
            rememberFailure(r, requestNames, began, why);
            throw e;
        }
    }

    private static final class ResponseTooLarge extends IOException {
        ResponseTooLarge(String message) { super(message); }
    }

    private static void rememberFailure(Request r, List<String> requestNames,
                                        long began, String error) {
        remember(new Diagnostic(System.currentTimeMillis(), r.provider(), r.model(), r.url(),
            List.copyOf(requestNames), new String(r.body(), StandardCharsets.UTF_8), 0,
            List.of(), "", System.currentTimeMillis() - began, error));
    }

    private static synchronized void remember(Diagnostic d) {
        DIAGNOSTICS.addFirst(d);
        while (DIAGNOSTICS.size() > MAX_DIAGNOSTICS) DIAGNOSTICS.removeLast();
    }

    private static synchronized void markLastError(Request request, String error) {
        Diagnostic d = DIAGNOSTICS.peekFirst();
        if (d == null || !d.provider().equals(request.provider())
            || !d.url().equals(request.url())) return;
        DIAGNOSTICS.removeFirst();
        DIAGNOSTICS.addFirst(new Diagnostic(d.at(), d.provider(), d.model(), d.url(),
            d.requestHeaders(), d.requestBody(), d.status(), d.responseHeaders(),
            d.responseBody(), d.elapsedMs(), error == null ? "" : error));
    }

    private static String readOpenAi(JsonObject reply) {
        String direct = string(reply, "output_text");
        if (!direct.isBlank()) return direct;
        StringBuilder text = new StringBuilder();
        JsonArray output = array(reply, "output");
        if (output != null) {
            for (JsonElement itemEl : output) {
                if (!itemEl.isJsonObject()) continue;
                JsonArray content = array(itemEl.getAsJsonObject(), "content");
                if (content == null) continue;
                for (JsonElement partEl : content) {
                    if (!partEl.isJsonObject()) continue;
                    JsonObject part = partEl.getAsJsonObject();
                    if ("output_text".equals(string(part, "type"))
                        || "text".equals(string(part, "type"))) {
                        text.append(string(part, "text"));
                    }
                }
            }
        }
        return text.toString();
    }

    private static String readOpenAiChat(JsonObject reply) {
        JsonArray choices = array(reply, "choices");
        if (choices != null && !choices.isEmpty() && choices.get(0).isJsonObject()) {
            JsonObject first = choices.get(0).getAsJsonObject();
            if (first.has("message") && first.get("message").isJsonObject()) {
                JsonElement content = first.getAsJsonObject("message").get("content");
                String text = contentText(content);
                if (!text.isBlank()) return text;
            }
            String text = string(first, "text");
            if (!text.isBlank()) return text;
        }
        // A few small runners return a minimal object instead of choices.
        String text = string(reply, "response");
        return text.isBlank() ? string(reply, "content") : text;
    }

    private static String readAnthropic(JsonObject reply) {
        StringBuilder text = new StringBuilder();
        JsonArray content = array(reply, "content");
        if (content != null) {
            for (JsonElement el : content) {
                if (!el.isJsonObject()) continue;
                JsonObject part = el.getAsJsonObject();
                if ("text".equals(string(part, "type"))) text.append(string(part, "text"));
            }
        }
        return text.toString();
    }

    private static String readGoogle(JsonObject reply) {
        StringBuilder text = new StringBuilder();
        JsonArray candidates = array(reply, "candidates");
        if (candidates != null) {
            for (JsonElement candidateEl : candidates) {
                if (!candidateEl.isJsonObject()) continue;
                JsonObject candidate = candidateEl.getAsJsonObject();
                if (!candidate.has("content") || !candidate.get("content").isJsonObject()) continue;
                JsonArray parts = array(candidate.getAsJsonObject("content"), "parts");
                if (parts == null) continue;
                for (JsonElement partEl : parts) {
                    if (partEl.isJsonObject()) text.append(string(partEl.getAsJsonObject(), "text"));
                }
            }
        }
        return text.toString();
    }

    private static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        if (!content.isJsonArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonElement el : content.getAsJsonArray()) {
            if (el.isJsonObject()) text.append(string(el.getAsJsonObject(), "text"));
        }
        return text.toString();
    }

    private static String noTextReason(Shape shape, JsonObject reply) {
        if (shape == Shape.OPENAI_RESPONSES) {
            String status = string(reply, "status");
            if ("incomplete".equals(status) && reply.has("incomplete_details")) {
                return "OpenAI stopped before producing text — "
                    + errorText(reply.get("incomplete_details"), "incomplete response");
            }
        } else if (shape == Shape.ANTHROPIC && "refusal".equals(string(reply, "stop_reason"))) {
            return "The model declined to answer this one.";
        } else if (shape == Shape.GOOGLE && reply.has("promptFeedback")) {
            return "Gemini returned no text — "
                + errorText(reply.get("promptFeedback"), "the prompt was blocked");
        }
        return "The model answered successfully but sent no text.";
    }

    private static String explain(int status, String body) {
        String detail = "";
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonObject() && el.getAsJsonObject().has("error")) {
                detail = errorText(el.getAsJsonObject().get("error"), "");
            }
        } catch (Exception ignored) {
            detail = body == null ? "" : body.strip();
        }
        if (detail.length() > 500) detail = detail.substring(0, 499) + "…";
        String head = switch (status) {
            case 401, 403 -> "The service rejected the API key";
            case 404 -> "No such model, or the wrong address";
            case 408 -> "The service timed out";
            case 409 -> "The service could not run this request in its current state";
            case 422 -> "The service did not understand this request";
            case 429 -> "Rate limited — too many requests";
            case 500, 502, 503, 504 -> "The service is having trouble";
            default -> "The service said " + status;
        };
        return detail.isBlank() ? head : head + " — " + detail;
    }

    private static String errorText(JsonElement el, String fallback) {
        if (el == null || el.isJsonNull()) return fallback;
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            for (String key : List.of("message", "detail", "reason", "blockReason", "code")) {
                String value = string(o, key);
                if (!value.isBlank()) return value;
            }
            return o.toString();
        }
        return el.toString();
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
    }

    private static String string(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()
            || !o.get(key).isJsonPrimitive()) return "";
        try { return o.get(key).getAsString(); }
        catch (RuntimeException e) { return ""; }
    }

    private static String trimSlashes(String base) {
        String out = base == null ? "" : base.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String endpoint(String provider, String normal) {
        String override = endpointOverrides.get(provider);
        return override == null || override.isBlank() ? normal : override;
    }

    private static String hostOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
        } catch (RuntimeException e) {
            return url;
        }
    }
}
