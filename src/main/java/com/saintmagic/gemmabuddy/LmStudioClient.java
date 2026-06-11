package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tiny OpenAI-compatible client for LM Studio.
 *
 * This stays intentionally small so the mod stays easy to reason about:
 * - one endpoint
 * - one request method
 * - content and reasoning parsing for thinking models
 */
public final class LmStudioClient {
    private final URI endpoint;
    private final String model;
    private final HttpClient client;

    public LmStudioClient(String endpoint, String model) {
        this.endpoint = URI.create(endpoint);
        this.model = model;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public LmStudioResponse complete(String systemPrompt, String userPrompt, int maxTokens)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.2);
        body.addProperty("max_tokens", maxTokens);

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("LM Studio HTTP " + response.statusCode() + ": " + response.body());
        }

        return parseResponse(response.body());
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

            String content = readText(message, "content");
            String reasoning = readText(message, "reasoning_content");
            return new LmStudioResponse(content, reasoning, body);
        } catch (RuntimeException ex) {
            return new LmStudioResponse("", "", body);
        }
    }

    private static JsonObject firstMessage(JsonObject response) {
        if (!response.has("choices") || !response.get("choices").isJsonArray()) {
            return null;
        }

        JsonArray choices = response.getAsJsonArray("choices");
        if (choices.isEmpty()) {
            return null;
        }

        JsonElement firstChoice = choices.get(0);
        if (!firstChoice.isJsonObject()) {
            return null;
        }

        JsonObject choice = firstChoice.getAsJsonObject();
        if (!choice.has("message") || !choice.get("message").isJsonObject()) {
            return null;
        }

        return choice.getAsJsonObject("message");
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

    public record LmStudioResponse(String content, String reasoningContent, String rawBody) {
    }
}
