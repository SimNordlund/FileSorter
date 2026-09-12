package com.example.FileSorter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Optional local video metadata reader. Never converts videos or requests network inputs. */
@Component
public class FfprobeService {
    private static final int MAX_OUTPUT_BYTES = 1_048_576;
    private final ObjectMapper mapper;
    private final String executable;
    private final int timeoutSeconds;

    public FfprobeService(ObjectMapper mapper,
                          @Value("${filesorter.ffprobe-path:}") String configuredPath,
                          @Value("${filesorter.ffprobe-timeout-seconds:20}") int timeoutSeconds) {
        this.mapper = mapper;
        this.executable = findExecutable(configuredPath);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public boolean available() { return executable != null; }
    public Session openSession(BooleanSupplier cancelled) { return new Session(cancelled); }

    private static String findExecutable(String configuredPath) {
        try {
            if (!configuredPath.isBlank()) {
                Path path = Path.of(configuredPath).toAbsolutePath().normalize();
                return Files.isRegularFile(path) && Files.isExecutable(path) ? path.toString() : null;
            }
        } catch (InvalidPathException ignored) {
            return null;
        }
        String searchPath = System.getenv("PATH");
        for (String directory : (searchPath == null ? "" : searchPath).split(File.pathSeparator)) {
            if (directory.isBlank()) continue;
            for (String name : List.of("ffprobe.exe", "ffprobe")) {
                try {
                    Path path = Path.of(directory.replace("\"", "")).resolve(name);
                    if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toAbsolutePath().toString();
                } catch (InvalidPathException ignored) {
                    // Ignore unrelated invalid PATH entries.
                }
            }
        }
        return null;
    }

    private record Output(byte[] json, int exitCode) { }

    public final class Session implements AutoCloseable {
        private final BooleanSupplier cancelled;
        private final ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "ffprobe-output");
            thread.setDaemon(true);
            return thread;
        });
        private String failure;

        private Session(BooleanSupplier cancelled) { this.cancelled = cancelled; }
        public String failure() { return failure; }

        public Map<String, String> read(Path file) {
            if (!available() || failure != null) return Map.of();
            Process process = null;
            Future<Output> response = null;
            try {
                checkCancelled();
                ProcessBuilder command = new ProcessBuilder(executable,
                        "-v", "error", "-hide_banner", "-protocol_whitelist", "file",
                        "-select_streams", "V", "-show_entries", "format_tags:stream=codec_type:stream_tags",
                        "-of", "json", "-i", file.toAbsolutePath().toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                // FFREPORT could otherwise create a file even during a read-only preview.
                command.environment().keySet().removeIf(key -> key.equalsIgnoreCase("FFREPORT"));
                process = command.start();
                process.getOutputStream().close();
                Process running = process;
                response = reader.submit(() -> {
                    try (InputStream output = running.getInputStream()) {
                        byte[] json = output.readNBytes(MAX_OUTPUT_BYTES + 1);
                        if (json.length > MAX_OUTPUT_BYTES) throw new IOException("Svaret med videometadata överstiger 1 MB");
                        return new Output(json, running.waitFor());
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
                Output output;
                while (true) {
                    checkCancelled();
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException();
                    try {
                        output = response.get(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
                        break;
                    } catch (TimeoutException exception) {
                        if (System.nanoTime() >= deadline) throw exception;
                    }
                }
                // An unrecognized individual file must not disable the reader for other videos.
                if (output.exitCode() != 0) return Map.of();
                JsonNode document = mapper.readTree(output.json());
                if (document == null || !document.isObject()) return Map.of();
                boolean hasVideo = false;
                for (JsonNode stream : document.path("streams")) {
                    if (stream.path("codec_type").asText().equals("video")) hasVideo = true;
                }
                if (!hasVideo) return Map.of();
                Map<String, String> tags = new LinkedHashMap<>();
                appendTags(tags, "fil", document.path("format").path("tags"));
                int streamIndex = 0;
                for (JsonNode stream : document.path("streams")) {
                    appendTags(tags, "videoström " + streamIndex++, stream.path("tags"));
                }
                return tags;
            } catch (CancellationException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } catch (Exception exception) {
                failure = exception instanceof TimeoutException
                        ? "ffprobe svarade inte i tid. Återstående videor använder övriga metadataläsare och datum i filnamnen."
                        : "ffprobe är inte längre tillgängligt. Återstående videor använder övriga metadataläsare och datum i filnamnen.";
                return Map.of();
            } finally {
                if (response != null) response.cancel(true);
                if (process != null) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }
        }

        private void checkCancelled() {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }

        private void appendTags(Map<String, String> target, String scope, JsonNode tags) {
            tags.fields().forEachRemaining(tag -> {
                if (tag.getValue().isTextual()) target.put(scope + ":" + tag.getKey(), tag.getValue().asText());
            });
        }

        @Override
        public void close() { reader.shutdownNow(); }
    }
}
