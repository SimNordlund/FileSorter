package com.example.FileSorter;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

@Service
public class PhotoOrganizerService {
    public static final String UNHANDLED_FOLDER = "EjHanterade";
    private final PhotoDateResolver dateResolver;
    private final ExifToolService exifTool;
    private final FfprobeService ffprobe;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "photo-organizer");
        thread.setDaemon(false);
        return thread;
    });
    private volatile Job current;

    public PhotoOrganizerService(PhotoDateResolver dateResolver, ExifToolService exifTool, FfprobeService ffprobe) {
        this.dateResolver = dateResolver;
        this.exifTool = exifTool;
        this.ffprobe = ffprobe;
    }

    public synchronized Snapshot start(OrganizeRequest request) throws IOException {
        if (current != null && current.active()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "En körning pågår redan. Vänta tills den är klar eller avbryt den först.");
        }
        if (request.sourceDir() == null || request.sourceDir().isBlank()) {
            throw new IllegalArgumentException("Välj en källmapp först.");
        }
        Path source = Path.of(request.sourceDir().trim()).toRealPath();
        if (!Files.isDirectory(source) || !Files.isReadable(source)) {
            throw new IllegalArgumentException("Källan måste vara en mapp som går att läsa.");
        }
        Path output = request.outputDir() == null || request.outputDir().isBlank()
                ? source.resolve("SortedPictures") : Path.of(request.outputDir().trim()).toAbsolutePath().normalize();
        output = resolveDestination(output);
        if (source.startsWith(output)) {
            throw new IllegalArgumentException("Välj en målmapp inuti källmappen eller på en annan plats. Målmappen får inte vara själva källmappen eller en av dess överordnade mappar.");
        }
        Job job = new Job(source, output, request);
        current = job;
        worker.submit(() -> run(job));
        return job.snapshot();
    }

    public Snapshot current() {
        Job job = current;
        return job == null ? null : job.snapshot();
    }
    public Snapshot get(String id) { return requireJob(id).snapshot(); }

    public Snapshot cancel(String id) {
        Job job = requireJob(id);
        synchronized (job) {
            if (job.active()) job.cancelled = true;
        }
        return job.snapshot();
    }

    private Job requireJob(String id) {
        Job job = current;
        if (job == null || !job.id.equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Körningen hittades inte. Endast den senaste körningen sparas i minnet.");
        return job;
    }

    private static Path resolveDestination(Path output) throws IOException {
        Path existing = output;
        while (existing != null && !Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null) throw new IllegalArgumentException("Det finns ingen överordnad mapp för den angivna målsökvägen.");
        if (!Files.isDirectory(existing)) throw new IllegalArgumentException("Målsökvägen eller en av dess överordnade sökvägar är inte en mapp.");
        return existing.toRealPath().resolve(existing.relativize(output)).normalize();
    }

    private void run(Job job) {
        try (ExifToolService.Session metadata = exifTool.openSession();
             FfprobeService.Session videoMetadata = ffprobe.openSession(() -> job.cancelled)) {
            checkCancelled(job);
            if (!job.request.isPreview()) {
                ensureDirectory(job.output);
                Path report = job.output.resolve("file-sorter-report-" + job.id + ".csv");
                job.report = report.toString();
                try (BufferedWriter writer = Files.newBufferedWriter(report, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    writer.write('\uFEFF');
                    writer.write("source,destination,result,date,date_source,reason\r\n");
                    walk(job, metadata, videoMetadata, writer);
                }
            } else {
                walk(job, metadata, videoMetadata, null);
            }
            if (metadata.failure() != null) job.warning(metadata.failure());
            if (videoMetadata.failure() != null) job.warning(videoMetadata.failure());
            job.finish(job.cancelled ? "CANCELLED" : job.errorCount() > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED");
        } catch (CancellationException exception) {
            job.finish("CANCELLED");
        } catch (Exception exception) {
            job.error("Körningen stoppades: " + message(exception));
            job.finish("FAILED");
        }
    }

    private void walk(Job job, ExifToolService.Session metadata, FfprobeService.Session videoMetadata,
                      BufferedWriter report) throws IOException {
        // A streaming walk avoids loading a large collection or its image contents into memory.
        Files.walkFileTree(job.source, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                checkCancelled(job);
                if (directory.startsWith(job.output)) return FileVisitResult.SKIP_SUBTREE;
                if (!directory.equals(job.source) && !job.request.includeSubfolders()) return FileVisitResult.SKIP_SUBTREE;
                if (!directory.toRealPath().equals(directory.toAbsolutePath().normalize())) {
                    job.skip(directory, "Mapplänk eller kopplingspunkt hoppades över");
                    row(report, directory, null, "skipped", null, "Mapplänk eller kopplingspunkt hoppades över");
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                checkCancelled(job);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    job.skip(file, "Länk eller specialfil hoppades över");
                    row(report, file, null, "skipped", null, "Länk eller specialfil hoppades över");
                    return FileVisitResult.CONTINUE;
                }
                job.beginFile(file);
                PhotoDateResolver.Result date = null;
                Path destination = null;
                String outcome = "error";
                String detail;
                try {
                    verifySource(file, attributes);
                    date = dateResolver.resolve(file, attributes, job.request.allowFileDates(), metadata, videoMetadata);
                    checkCancelled(job);
                    String group = date.handled() ? YearMonth.from(date.date()).toString() : UNHANDLED_FOLDER;
                    Path folder = job.output.resolve(group);
                    validateOutputParent(folder);
                    if (!job.request.isPreview()) ensureDirectory(folder);
                    Placement placement = place(job, file, attributes, folder, date);
                    destination = placement.path();
                    outcome = placement.duplicate() ? (job.request.isPreview() ? "duplicate (preview)" : "already present") : job.request.isPreview() ? "preview"
                            : job.request.effectiveMode() == OrganizeRequest.Mode.MOVE ? "moved" : "copied";
                    detail = placement.duplicate() ? (job.request.isPreview()
                            ? "En identisk målfil finns redan eller är planerad. Originalet skulle behållas. "
                            : "En identisk målfil finns redan. Originalet behölls. ") + date.reason() : date.reason();
                    job.placed(file, destination, group, date, outcome, placement.duplicate(), detail);
                } catch (CancellationException exception) {
                    throw exception;
                } catch (Exception exception) {
                    if (exception instanceof CopyRetainedException retained) destination = retained.destination;
                    detail = message(exception);
                    job.fileError(file, destination, date, detail);
                }
                // Report failures stop the job: subsequent mutations must not silently lose their audit trail.
                row(report, file, destination, outcome, date, detail);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                checkCancelled(job);
                job.fileError(file, null, null, message(exception));
                row(report, file, null, "error", null, message(exception));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    job.error(directory + ": " + message(exception));
                    row(report, directory, null, "error", null, message(exception));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record Placement(Path path, boolean duplicate) { }

    private static final class CopyRetainedException extends IOException {
        private final Path destination;

        private CopyRetainedException(Path destination, IOException cause) {
            super("En kontrollerad kopia sparades i " + destination + ", men originalet kunde inte tas bort: " + message(cause), cause);
            this.destination = destination;
        }
    }

    private Placement place(Job job, Path source, BasicFileAttributes original, Path folder,
                            PhotoDateResolver.Result date) throws IOException {
        String filename = source.getFileName().toString();
        boolean dated = date.handled();
        if (dated) {
            int dot = filename.lastIndexOf('.');
            String extension = dot > 0 ? filename.substring(dot) : "";
            filename = date.date().format(DateTimeFormatter.BASIC_ISO_DATE) + extension;
        }
        int number = 1;
        Path temporary = null;
        try {
            while (true) {
                checkCancelled(job);
                Path destination = folder.resolve(number == 1 ? filename : numberedFilename(filename, number, dated));
                if (Files.exists(destination, NOFOLLOW_LINKS)) {
                    if (Files.isRegularFile(destination, NOFOLLOW_LINKS) && !Files.isSymbolicLink(destination)
                            && destination.toRealPath().equals(destination.toAbsolutePath().normalize())
                            && Files.size(destination) == original.size() && sameContents(job, source, destination)) {
                        verifySource(source, original);
                        return new Placement(destination, true);
                    }
                    number++;
                    continue;
                }
                if (job.request.isPreview()) {
                    // Reserve names only during previews. Real jobs reserve names on the filesystem.
                    Path plannedSource = job.previewNames.get(destination);
                    if (plannedSource != null) {
                        if (Files.isRegularFile(plannedSource, NOFOLLOW_LINKS)
                                && plannedSource.toRealPath().equals(plannedSource.toAbsolutePath().normalize())
                                && Files.size(plannedSource) == original.size() && sameContents(job, source, plannedSource)) {
                            return new Placement(destination, true);
                        }
                        number++;
                        continue;
                    }
                    job.previewNames.put(destination, source);
                    return new Placement(destination, false);
                }
                if (temporary == null) {
                    ensureDirectory(folder);
                    temporary = Files.createTempFile(folder, ".file-sorter-", ".part");
                    copyFile(job, source, temporary);
                    verifySource(source, original);
                    if (Files.size(temporary) != original.size() || !sameContents(job, source, temporary)) {
                        throw new IOException("Kontrollen av kopian misslyckades. Originalet behölls");
                    }
                    try {
                        Files.setLastModifiedTime(temporary, original.lastModifiedTime());
                    } catch (IOException exception) {
                        job.warning("Vissa datum i filsystemet kunde inte bevaras. Filernas inbäddade metadata och innehåll är oförändrade.");
                    }
                }
                checkCancelled(job);
                verifySource(source, original);
                ensureDirectory(folder);
                try {
                    // Do not use ATOMIC_MOVE: providers may replace existing targets with that option.
                    Files.move(temporary, destination);
                    temporary = null;
                } catch (FileAlreadyExistsException exception) {
                    number++;
                    continue;
                }
                if (job.request.effectiveMode() == OrganizeRequest.Mode.MOVE) {
                    try {
                        verifySource(source, original);
                        Files.delete(source);
                    } catch (IOException exception) {
                        throw new CopyRetainedException(destination, exception);
                    }
                }
                return new Placement(destination, false);
            }
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException exception) { job.warning("Den tillfälliga kopian kunde inte tas bort: " + temporary); }
            }
        }
    }

    private static void copyFile(Job job, Path source, Path destination) throws IOException {
        try (InputStream input = Files.newInputStream(source, NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(destination, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(job);
                output.write(buffer, 0, count);
            }
        }
    }

    private static boolean sameContents(Job job, Path source, Path destination) throws IOException {
        try (InputStream left = Files.newInputStream(source, NOFOLLOW_LINKS);
             InputStream right = Files.newInputStream(destination, NOFOLLOW_LINKS)) {
            byte[] a = new byte[256 * 1024];
            byte[] b = new byte[a.length];
            while (true) {
                checkCancelled(job);
                int sizeA = left.readNBytes(a, 0, a.length);
                int sizeB = right.readNBytes(b, 0, b.length);
                if (sizeA != sizeB || !Arrays.equals(a, 0, sizeA, b, 0, sizeB)) return false;
                if (sizeA == 0) return true;
            }
        }
    }

    private static String numberedFilename(String filename, int number, boolean dated) {
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        String suffix = (dated ? Integer.toString(number - 1) : " (" + number + ")") + extension;
        return stem.substring(0, Math.min(stem.length(), Math.max(1, 250 - suffix.length()))) + suffix;
    }

    private static void validateOutputParent(Path directory) throws IOException {
        Path existing = directory;
        while (existing != null && !Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || !Files.isDirectory(existing, NOFOLLOW_LINKS)
                || !existing.toRealPath().equals(existing.toAbsolutePath().normalize())) {
            throw new IOException("Målmappens överordnade mapp är otillgänglig eller innehåller en länk eller kopplingspunkt: " + directory);
        }
    }

    private static void ensureDirectory(Path directory) throws IOException {
        validateOutputParent(directory);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(directory.toAbsolutePath().normalize())) {
            throw new IOException("Målmappen innehåller en länk eller kopplingspunkt: " + directory);
        }
    }

    private static void verifySource(Path file, BasicFileAttributes original) throws IOException {
        BasicFileAttributes now = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!now.isRegularFile() || now.isSymbolicLink() || now.size() != original.size()
                || !now.lastModifiedTime().equals(original.lastModifiedTime())
                || !Objects.equals(now.fileKey(), original.fileKey())
                || !file.toRealPath().equals(file.toAbsolutePath().normalize())) {
            throw new IOException("Originalfilen ändrades eller ersattes av en länk under bearbetningen. Originalet behölls");
        }
    }

    private static void checkCancelled(Job job) {
        if (job.cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static void row(BufferedWriter writer, Path source, Path destination, String result,
                            PhotoDateResolver.Result date, String reason) throws IOException {
        if (writer == null) return;
        List<String> fields = List.of(source.toString(), destination == null ? "" : destination.toString(), result,
                date == null || date.date() == null ? "" : date.date().toString(), date == null ? "" : date.source(), reason);
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) writer.write(',');
            String value = fields.get(i);
            if (value.matches("(?s)^[\\s]*[=+@-].*")) value = "'" + value;
            writer.write('"');
            writer.write(value.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write("\r\n");
        writer.flush();
    }

    private static String message(Exception exception) {
        return SwedishMessages.error(exception);
    }

    @PreDestroy
    public void shutdown() {
        if (current != null) current.cancelled = true;
        worker.shutdownNow();
    }

    public record Sample(String source, String destination, String dateSource, String result, String reason) { }
    public record Snapshot(String id, String status, boolean cancelRequested, String sourceDir, String outputDir,
                           OrganizeRequest.Mode mode, boolean preview, boolean recursive, boolean useFileDates,
                           long processed, long dated, long unhandled, long alreadyPresent, long errors, long skipped,
                           String currentFile, Map<String, Long> groups, Map<String, Long> dateSources,
                           List<Sample> samples, List<String> errorMessages, List<String> warnings,
                           String report, Instant startedAt, Instant finishedAt) { }

    private static final class Job {
        private final String id = UUID.randomUUID().toString();
        private final Path source;
        private final Path output;
        private final OrganizeRequest request;
        private final Instant started = Instant.now();
        private final Map<String, Long> groups = new TreeMap<>();
        private final Map<String, Long> dateSources = new TreeMap<>();
        private final List<Sample> samples = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private final Map<Path, Path> previewNames = new HashMap<>();
        private volatile boolean cancelled;
        private volatile String report;
        private String status = "RUNNING";
        private String currentFile = "";
        private Instant finished;
        private long processed, dated, unhandled, alreadyPresent, errorCount, skipped;

        private Job(Path source, Path output, OrganizeRequest request) {
            this.source = source;
            this.output = output;
            this.request = request;
        }

        synchronized boolean active() { return status.equals("RUNNING"); }
        synchronized long errorCount() { return errorCount; }
        synchronized void beginFile(Path file) { currentFile = source.relativize(file).toString(); }
        synchronized void finish(String value) {
            status = value;
            finished = Instant.now();
            currentFile = "";
            previewNames.clear();
        }
        synchronized void warning(String warning) { if (warnings.size() < 30) warnings.add(warning); }
        synchronized void error(String error) {
            errorCount++;
            if (errors.size() < 100) errors.add(error);
        }
        synchronized void fileError(Path file, Path destination, PhotoDateResolver.Result date, String detail) {
            processed++;
            error(file + ": " + detail);
            sample(new Sample(file.toString(), destination == null ? "" : destination.toString(), date == null ? "none" : date.source(), "error", detail));
        }
        synchronized void skip(Path file, String reason) {
            skipped++;
            sample(new Sample(file.toString(), "", "none", "skipped", reason));
        }
        synchronized void placed(Path file, Path destination, String group, PhotoDateResolver.Result date,
                                 String result, boolean duplicate, String detail) {
            processed++;
            if (duplicate) alreadyPresent++;
            else {
                if (date.handled()) dated++; else unhandled++;
                groups.merge(group, 1L, Long::sum);
                dateSources.merge(date.source(), 1L, Long::sum);
            }
            sample(new Sample(file.toString(), destination.toString(), date.source(), result, detail));
        }
        private void sample(Sample sample) {
            if (samples.size() == 80) samples.remove(0);
            samples.add(sample);
        }
        synchronized Snapshot snapshot() {
            return new Snapshot(id, status, cancelled, source.toString(), output.toString(), request.effectiveMode(),
                    request.isPreview(), request.includeSubfolders(), request.allowFileDates(), processed, dated,
                    unhandled, alreadyPresent, errorCount, skipped, currentFile, new TreeMap<>(groups),
                    new TreeMap<>(dateSources), List.copyOf(samples), List.copyOf(errors), List.copyOf(warnings),
                    report, started, finished);
        }
    }
}
