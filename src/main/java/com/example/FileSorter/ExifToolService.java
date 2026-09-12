package com.example.FileSorter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Optional, read-only metadata reader. One persistent process per job, not per photo. */
@Component
public class ExifToolService {
    private final ObjectMapper mapper;
    private final String executable;
    private final int timeoutSeconds;

    public ExifToolService(ObjectMapper mapper,
                           @Value("${filesorter.exiftool-path:}") String configuredPath,
                           @Value("${filesorter.exiftool-timeout-seconds:20}") int timeoutSeconds) {
        this.mapper = mapper;
        this.executable = findExecutable(configuredPath);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public boolean available() { return executable != null; }
    public Session openSession() { return new Session(); }

    private String findExecutable(String configuredPath) {
        if (!configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath).toAbsolutePath().normalize();
            return Files.isRegularFile(configured) ? configured.toString() : null;
        }
        // getenv(name) respects Windows' case-insensitive environment variable names (Path/PATH).
        String searchPath = System.getenv("PATH");
        for (String directory : (searchPath == null ? "" : searchPath).split(File.pathSeparator)) {
            if (directory.isBlank()) continue;
            for (String name : List.of("exiftool.exe", "exiftool")) {
                try {
                    Path candidate = Path.of(directory.replace("\"", "")).resolve(name);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return candidate.toAbsolutePath().toString();
                    }
                } catch (InvalidPathException ignored) {
                    // Ignore an unrelated invalid PATH entry.
                }
            }
        }
        return null;
    }

    public final class Session implements AutoCloseable {
        private Process process;
        private BufferedWriter input;
        private BufferedReader output;
        private final ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "exiftool-output");
            thread.setDaemon(true);
            return thread;
        });
        private int requestId;
        private String failure;

        public String failure() { return failure; }

        public Map<String, String> read(Path file) {
            if (!available() || failure != null) return Map.of();
            String filename = file.toAbsolutePath().toString();
            if (filename.contains("\n") || filename.contains("\r")) return Map.of();
            Future<String> response = null;
            try {
                if (process == null) {
                    process = new ProcessBuilder(executable, "-stay_open", "True", "-@", "-")
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                    output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                }
                int id = ++requestId;
                // No write options are ever passed. Absolute filenames cannot be parsed as options.
                for (String option : List.of("-json", "-s", "-G1", "-charset", "filename=UTF8",
                        "-charset", "UTF8", "-api", "LargeFileSupport=1",
                        "-DateTimeOriginal", "-CreationDate", "-CreateDate", "-MediaCreateDate",
                        "-TrackCreateDate", "-DateCreated", "-DigitalCreationDate", "-DateTimeDigitized",
                        "-DateTimeCreated", "-ContentCreateDate", "-DateUTC", "-CreationTime",
                        "-ShotDate", "-DateTimeRecorded", "-RecordedDate", "-Error")) {
                    input.write(option);
                    input.newLine();
                }
                input.write(filename);
                input.newLine();
                input.write("-execute" + id);
                input.newLine();
                input.flush();
                response = reader.submit(() -> {
                    StringBuilder json = new StringBuilder();
                    String line;
                    while ((line = output.readLine()) != null) {
                        if (line.equals("{ready" + id + "}")) return json.toString();
                        if (json.length() + line.length() > 1_048_576) {
                            throw new IOException("Metadatasvaret överstiger 1 MB");
                        }
                        json.append(line).append('\n');
                    }
                    throw new IOException("ExifTool avslutade oväntat sin utmatning");
                });
                JsonNode document = mapper.readTree(response.get(timeoutSeconds, TimeUnit.SECONDS));
                Map<String, String> tags = new LinkedHashMap<>();
                if (document != null && document.isArray() && !document.isEmpty()) {
                    document.get(0).fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
                }
                return tags;
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                failure = exception instanceof TimeoutException
                        ? "ExifTool svarade inte i tid. Återstående filer använder övriga tillgängliga läsare och datum i filnamnen."
                        : "ExifTool är inte längre tillgängligt. Återstående filer använder övriga tillgängliga läsare och datum i filnamnen.";
                if (response != null) response.cancel(true);
                close();
                return Map.of();
            }
        }

        @Override
        public void close() {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            reader.shutdownNow();
            try { if (input != null) input.close(); } catch (IOException ignored) { }
            try { if (output != null) output.close(); } catch (IOException ignored) { }
        }
    }
}
