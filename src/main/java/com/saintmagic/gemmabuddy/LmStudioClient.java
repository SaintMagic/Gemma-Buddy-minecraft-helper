package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

/**
 * Small OpenAI-compatible LM Studio client with explicit local model profiles.
 */
public final class LmStudioClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final GemmaBuddyConfig config;
    private final HttpClient client;
    private final AtomicReference<ResponseStatus> connectionStatus = new AtomicReference<>(ResponseStatus.UNKNOWN);
    private volatile String lastError = "";

    public LmStudioClient(GemmaBuddyConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public LmStudioResponse complete(String systemPrompt, String userPrompt, int maxTokens)
            throws IOException, InterruptedException {
        return complete(systemPrompt, userPrompt, RequestProfile.NORMAL_QA, maxTokens, false);
    }

    public LmStudioResponse complete(String systemPrompt, String userPrompt, RequestProfile profile)
            throws IOException, InterruptedException {
        int maxTokens = profile == RequestProfile.PLANNER || profile == RequestProfile.HEAVY
                ? config.maxTokensPlanning()
                : config.maxTokensDefault();
        return complete(systemPrompt, userPrompt, profile, maxTokens, profile == RequestProfile.PLANNER);
    }

    public LmStudioResponse completeJson(String systemPrompt, String userPrompt)
            throws IOException, InterruptedException {
        String strictSystem = systemPrompt
                + "\nReturn one valid JSON object only. No markdown, code fence, commentary, analysis, or reasoning.";
        return complete(strictSystem, userPrompt, RequestProfile.PLANNER, config.maxTokensPlanning(), true);
    }

    private LmStudioResponse complete(String systemPrompt, String userPrompt, RequestProfile profile, int maxTokens,
            boolean jsonMode) throws IOException, InterruptedException {
        boolean thinkingEnabled = shouldEnableThinking(profile);
        try {
            LmStudioResponse response = send(systemPrompt, userPrompt, profile, maxTokens, jsonMode, thinkingEnabled);
            updateResponseStatus(response);
            return response;
        } catch (java.net.http.HttpTimeoutException ex) {
            if (thinkingEnabled && config.retryWithoutThinkingOnTimeout()) {
                try {
                    connectionStatus.set(ResponseStatus.RETRYING_WITHOUT_THINKING);
                    LmStudioResponse response = send(
                            systemPrompt + "\nThinking is disabled. Give the final answer immediately.",
                            userPrompt, profile, maxTokens, jsonMode, false);
                    updateResponseStatus(response);
                    return response;
                } catch (IOException | InterruptedException retryFailure) {
                    markError(retryFailure);
                    throw retryFailure;
                }
            }
            markError(ex);
            throw ex;
        } catch (IOException | InterruptedException ex) {
            markError(ex);
            throw ex;
        }
    }

    private LmStudioResponse send(String systemPrompt, String userPrompt, RequestProfile profile, int maxTokens,
            boolean jsonMode, boolean thinkingEnabled) throws IOException, InterruptedException {
        URI endpoint;
        try {
            endpoint = URI.create(config.lmStudioEndpoint());
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid LM Studio endpoint in config.json", ex);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", config.modelName());
        body.addProperty("temperature", profile == RequestProfile.PLANNER || profile == RequestProfile.HEAVY
                ? config.temperaturePlanning()
                : config.temperatureDefault());
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("enable_thinking", thinkingEnabled);
        if (!thinkingEnabled) {
            body.addProperty("reasoning_effort", "none");
            body.addProperty("reasoning", false);
            body.addProperty("thinking", false);
        }
        if (jsonMode) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.add("messages", messages);
        if (config.logLlmErrors()) {
            LOGGER.info("GemmaBuddy LM request profile={} jsonMode={} thinking={} maxTokens={} timeout={}s endpoint={}",
                    profile, jsonMode, thinkingEnabled, maxTokens, config.requestTimeoutSeconds(), endpoint);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("LM Studio HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }

        connectionStatus.set(ResponseStatus.CONNECTED);
        LmStudioResponse parsed = parseResponse(response.body());
        return new LmStudioResponse(sanitizeVisibleText(parsed.content()), parsed.reasoningContent(), parsed.rawBody());
    }

    public LmStudioResponse parseResponse(String body) {
        if (body == null || body.isBlank()) {
            return new LmStudioResponse("", "", "");
        }

        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonObject message = firstMessage(json);
            if (message == null) {
                return new LmStudioResponse("", "", body);
            }
            return new LmStudioResponse(readText(message, "content"), readText(message, "reasoning_content"), body);
        } catch (RuntimeException ex) {
            return new LmStudioResponse("", "", body);
        }
    }

    public String sanitizeVisibleText(String text) {
        String cleaned = text == null ? "" : text.trim();
        cleaned = cleaned.replaceAll("(?is)<\\|channel\\|>\\s*(analysis|thought).*?(?=<\\|channel\\|>|$)", "");
        cleaned = cleaned.replaceAll("(?is)<think>.*?</think>", "");
        cleaned = cleaned.replaceAll("(?is)```(?:analysis|thought|reasoning).*?```", "");
        cleaned = cleaned.replaceAll("(?is)^\\s*(analysis|thought|reasoning|draft)\\s*:\\s*.*?(?:\\n\\s*\\n|$)", "");
        return cleaned.trim();
    }

    public String connectionStatusLine() {
        return switch (connectionStatus.get()) {
            case CONNECTED -> "Connected";
            case MODEL_RESPONDED -> "Model responded";
            case EMPTY_VISIBLE_CONTENT -> "Visible content empty";
            case INVALID_PLANNER_JSON -> "Invalid planner JSON";
            case REASONING_SUPPRESSED -> "Reasoning suppressed";
            case RETRYING_WITHOUT_THINKING -> "Retrying without thinking";
            case FALLBACK_USED -> "Fallback used";
            case DISCONNECTED -> "Disconnected" + (lastError.isBlank() ? "" : ": " + abbreviate(lastError));
            case UNKNOWN -> "Not tested";
        };
    }

    public ResponseStatus responseStatus() {
        return connectionStatus.get();
    }

    public void markInvalidPlannerJson() {
        connectionStatus.set(ResponseStatus.INVALID_PLANNER_JSON);
    }

    public void markFallbackUsed() {
        connectionStatus.set(ResponseStatus.FALLBACK_USED);
    }

    public void markReasoningSuppressed() {
        connectionStatus.set(ResponseStatus.REASONING_SUPPRESSED);
    }

    public boolean thinkingOffCompatibilityFieldsEnabled() {
        return true;
    }

    private void updateResponseStatus(LmStudioResponse response) {
        lastError = "";
        if (response != null && !sanitizeVisibleText(response.content()).isBlank()) {
            connectionStatus.set(ResponseStatus.MODEL_RESPONDED);
        } else if (response != null && !response.reasoningContent().isBlank()) {
            connectionStatus.set(ResponseStatus.REASONING_SUPPRESSED);
            LOGGER.warn("LM Studio returned hidden reasoning while visible content was blank. Reasoning was suppressed.");
        } else {
            connectionStatus.set(ResponseStatus.EMPTY_VISIBLE_CONTENT);
        }
    }

    private void markError(Exception ex) {
        connectionStatus.set(ResponseStatus.DISCONNECTED);
        lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private boolean shouldEnableThinking(RequestProfile profile) {
        return switch (config.thinkingMode()) {
            case OFF -> false;
            case ON -> true;
            case AUTO -> profile == RequestProfile.PLANNER || profile == RequestProfile.HEAVY;
        };
    }

    private static JsonObject firstMessage(JsonObject response) {
        if (!response.has("choices") || !response.get("choices").isJsonArray()) {
            return null;
        }
        JsonArray choices = response.getAsJsonArray("choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return null;
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        return choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message")
                : null;
    }

    private static String readText(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static JsonObject message(String role, String content) {
        JsonObject object = new JsonObject();
        object.addProperty("role", role);
        object.addProperty("content", content);
        return object;
    }

    private static String abbreviate(String text) {
        String value = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        return value.length() > 300 ? value.substring(0, 297) + "..." : value;
    }

    public enum RequestProfile {
        FAST_FACTUAL,
        NORMAL_QA,
        PLANNER,
        HEAVY;

        public static RequestProfile parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "fast" -> FAST_FACTUAL;
                case "planner" -> PLANNER;
                case "heavy" -> HEAVY;
                default -> NORMAL_QA;
            };
        }
    }

    public enum ResponseStatus {
        UNKNOWN,
        DISCONNECTED,
        CONNECTED,
        MODEL_RESPONDED,
        EMPTY_VISIBLE_CONTENT,
        INVALID_PLANNER_JSON,
        REASONING_SUPPRESSED,
        RETRYING_WITHOUT_THINKING,
        FALLBACK_USED
    }

    public record LmStudioResponse(String content, String reasoningContent, String rawBody) {
    }
}
