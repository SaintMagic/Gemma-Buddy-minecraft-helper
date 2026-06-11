package com.saintmagic.gemmabuddy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

import net.minecraft.client.Minecraft;

/**
 * Push-to-talk voice capture and LM Studio transcription helper.
 *
 * This is intentionally lightweight and local-only. It records from the default
 * microphone, sends the audio to an OpenAI-compatible transcription endpoint,
 * and drops the transcript into the GemmaBuddy UI input.
 */
public final class VoiceControlManager {
    private enum VoiceState {
        DISABLED,
        EXPERIMENTAL,
        READY,
        ERROR
    }

    private static final URI TRANSCRIPTION_ENDPOINT = URI.create("http://localhost:1234/v1/audio/transcriptions");
    private static final String TRANSCRIPTION_MODEL = firstNonBlank(
            System.getenv("GEMMABUDDY_STT_MODEL"),
            System.getenv("STT_MODEL"),
            "whisper-1");
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(16000.0F, 16, 1, true, false);

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicReference<TargetDataLine> lineRef = new AtomicReference<>();
    private final AtomicReference<VoiceState> voiceState = new AtomicReference<>(VoiceState.DISABLED);
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    private final ExecutorService executor;
    private final HttpClient httpClient;

    public VoiceControlManager() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "GemmaBuddy-Voice");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isRecording() {
        return recording.get();
    }

    public String statusLine() {
        if (!GemmaBuddy.config().enableVoiceControl()) {
            return "Voice: Disabled";
        }

        if (voiceState.get() == VoiceState.ERROR || !lastError.get().isBlank()) {
            return "Voice: Error";
        }

        if (voiceState.get() == VoiceState.READY) {
            return "Voice: Ready";
        }

        return "Voice: Experimental";
    }

    public void toggleRecording() {
        if (!GemmaBuddy.config().enableVoiceControl()) {
            GemmaBuddyScreen.addHistory("GemmaBuddy: Voice control is disabled in config.");
            return;
        }

        if (isRecording()) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    public void tick(boolean voiceKeyDown) {
        if (!GemmaBuddy.config().enableVoiceControl()) {
            if (isRecording()) {
                stopRecording();
            }
            voiceState.set(VoiceState.DISABLED);
            return;
        }

        if (voiceKeyDown && !isRecording()) {
            startRecording();
        } else if (!voiceKeyDown && isRecording()) {
            stopRecording();
        }
    }

    private void startRecording() {
        if (!recording.compareAndSet(false, true)) {
            return;
        }

        lastError.set("");
        if (GemmaBuddy.config().enableVoiceControl()) {
            voiceState.set(VoiceState.EXPERIMENTAL);
        }
        GemmaBuddyScreen.addHistory("GemmaBuddy: Recording voice...");
        executor.submit(this::captureAndTranscribe);
    }

    private void stopRecording() {
        recording.set(false);
        TargetDataLine line = lineRef.getAndSet(null);
        if (line != null) {
            line.stop();
            line.close();
        }
    }

    private void captureAndTranscribe() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TargetDataLine line = null;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                recording.set(false);
                voiceState.set(VoiceState.ERROR);
                lastError.set("Voice capture is not supported on this system.");
                GemmaBuddyScreen.addHistory("GemmaBuddy: Voice capture is not supported on this system.");
                return;
            }

            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(AUDIO_FORMAT);
            line.start();
            lineRef.set(line);

            byte[] chunk = new byte[4096];
            while (recording.get()) {
                int read = line.read(chunk, 0, chunk.length);
                if (read > 0) {
                    buffer.write(chunk, 0, read);
                }
            }

            line.stop();
            line.close();
            lineRef.compareAndSet(line, null);

            if (buffer.size() == 0) {
                voiceState.set(VoiceState.ERROR);
                lastError.set("No voice was captured.");
                GemmaBuddyScreen.addHistory("GemmaBuddy: No voice was captured.");
                return;
            }

            Path wavPath = Files.createTempFile("gemmabuddy-voice-", ".wav");
            try (AudioInputStream audioInput = new AudioInputStream(
                    new ByteArrayInputStream(buffer.toByteArray()),
                    AUDIO_FORMAT,
                    buffer.size() / AUDIO_FORMAT.getFrameSize())) {
                AudioSystem.write(audioInput, AudioFileFormat.Type.WAVE, wavPath.toFile());
            }

            String transcript = transcribe(wavPath);
            Files.deleteIfExists(wavPath);
            voiceState.set(VoiceState.READY);
            lastError.set("");

            if (transcript.isBlank()) {
                GemmaBuddyScreen.addHistory("GemmaBuddy: Voice transcription returned nothing.");
                return;
            }

            LOGGER.info("GemmaBuddy voice transcript='{}'", transcript);
            Minecraft.getInstance().execute(() -> GemmaBuddyClient.applyVoiceTranscript(transcript));
        } catch (Exception ex) {
            recording.set(false);
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (Exception ignored) {
                }
            }
            lineRef.compareAndSet(line, null);
            voiceState.set(VoiceState.ERROR);
            lastError.set(friendlyError(ex));
            GemmaBuddyScreen.addHistory("GemmaBuddy: Voice capture failed: " + friendlyError(ex));
        } finally {
            recording.set(false);
        }
    }

    private String transcribe(Path wavPath) throws IOException, InterruptedException {
        String boundary = "----GemmaBuddyBoundary" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        writePart(body, boundary, "model", TRANSCRIPTION_MODEL, "text/plain; charset=utf-8", null);
        writePart(body, boundary, "file", wavPath, "audio/wav", "gemmabuddy-voice.wav");
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(TRANSCRIPTION_ENDPOINT)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("LM Studio transcription HTTP " + response.statusCode() + ": " + response.body());
        }

        String transcript = parseTranscript(response.body());
        return transcript == null ? "" : transcript.trim();
    }

    private static void writePart(ByteArrayOutputStream body, String boundary, String name, String value,
            String contentType, String filename) throws IOException {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        StringBuilder header = new StringBuilder();
        header.append("Content-Disposition: form-data; name=\"").append(name).append("\"");
        if (filename != null) {
            header.append("; filename=\"").append(filename).append("\"");
        }
        header.append("\r\n");
        body.write(header.toString().getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(value.getBytes(StandardCharsets.UTF_8));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writePart(ByteArrayOutputStream body, String boundary, String name, Path file, String contentType,
            String filename) throws IOException {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        StringBuilder header = new StringBuilder();
        header.append("Content-Disposition: form-data; name=\"").append(name).append("\"");
        if (filename != null) {
            header.append("; filename=\"").append(filename).append("\"");
        }
        header.append("\r\n");
        body.write(header.toString().getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(Files.readAllBytes(file));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String parseTranscript(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }

        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("text") && !json.get("text").isJsonNull()) {
                JsonElement text = json.get("text");
                if (text.isJsonPrimitive()) {
                    return text.getAsString();
                }
            }
            if (json.has("transcript") && !json.get("transcript").isJsonNull()) {
                JsonElement text = json.get("transcript");
                if (text.isJsonPrimitive()) {
                    return text.getAsString();
                }
            }
        } catch (RuntimeException ex) {
            return "";
        }

        return "";
    }

    private static String friendlyError(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String cleaned = message.trim().replaceAll("\\s+", " ");
        return cleaned.length() > 120 ? cleaned.substring(0, 117) + "..." : cleaned;
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
