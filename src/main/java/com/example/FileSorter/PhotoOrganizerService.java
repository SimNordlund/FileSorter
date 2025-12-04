package com.example.FileSorter;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PhotoOrganizerService {
    private static final DateTimeFormatter EXIF_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final Set<String> SUPPORTED_EXT =
            Set.of("jpg", "jpeg", "png", "heic", "webp", "mp4", "mov");

    public Map<String, Object> organizeDirectory(Path sourceDir, Path baseDir, String mode, boolean dryRun) {
        FilesystemMode fsMode = FilesystemMode.from(mode);
        Map<String, List<String>> placed = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        Map<String, Integer> dayCounters = new HashMap<>();

        try {
            if (!Files.isDirectory(sourceDir)) {
                throw new IllegalArgumentException("sourceDir is not a directory: " + sourceDir);
            }

            try (var stream = Files.list(sourceDir)) {
                stream
                        .filter(Files::isRegularFile)
                        .filter(p -> isSupported(p.getFileName().toString()))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .forEach(path -> {
                            try {
                                LocalDateTime mediaDate = extractPhotoDate(path).orElseGet(() -> fallbackFileTime(path));

                                Path targetDir;
                                String key;

                                if (mediaDate == null) {
                                    targetDir = baseDir.resolve("Unknown");
                                    key = "Unknown";

                                    Files.createDirectories(targetDir);
                                    String safeName = safeFilename(path.getFileName().toString());
                                    Path dest = uniquePath(targetDir.resolve(safeName));
                                    if (!dryRun) {
                                        write(path, dest, fsMode);
                                    }
                                    placed.computeIfAbsent(key, k -> new ArrayList<>()).add(dest.toString());
                                    return;
                                }

                                int year = mediaDate.getYear();
                                Month month = mediaDate.getMonth();

                                String monthFolder = String.format("%04d-%02d", year, month.getValue());
                                targetDir = baseDir.resolve(monthFolder);
                                key = monthFolder;

                                Files.createDirectories(targetDir);

                                String dateStr = mediaDate.toLocalDate().format(DATE_FMT);
                                String ext = getExtensionWithDot(path.getFileName().toString());
                                String newName = nextDatedFilename(targetDir, dateStr, ext, dayCounters);

                                Path dest = targetDir.resolve(newName);
                                if (!dryRun) {
                                    write(path, dest, fsMode);
                                }

                                placed.computeIfAbsent(key, k -> new ArrayList<>()).add(dest.toString());
                            } catch (Exception e) {
                                errors.add(path.getFileName() + ": " + e.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            errors.add("Top-level error: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceDir", sourceDir.toAbsolutePath().toString());
        result.put("baseDir", baseDir.toAbsolutePath().toString());
        result.put("mode", fsMode.name().toLowerCase(Locale.ROOT));
        result.put("dryRun", dryRun);
        result.put("groups", placed);
        result.put("errors", errors);
        result.put("totalPlaced", placed.values().stream().mapToInt(List::size).sum());
        return result;
    }

    public static Path defaultDesktopDir() {
        Path home = Path.of(System.getProperty("user.home"));
        return home.resolve("Desktop");
    }

    private boolean isSupported(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXT.contains(ext);
    }

    private Optional<LocalDateTime> extractPhotoDate(Path filePath) {
        String filename = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".mp4") || filename.endsWith(".mov")) return Optional.empty();

        try (InputStream in = Files.newInputStream(filePath)) {
            Metadata metadata = ImageMetadataReader.readMetadata(in);

            ExifSubIFDDirectory sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (sub != null) {
                String dto = sub.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                LocalDateTime parsed = tryParseExifDate(dto);
                if (parsed != null) return Optional.of(parsed);

                String dtd = sub.getString(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED);
                parsed = tryParseExifDate(dtd);
                if (parsed != null) return Optional.of(parsed);
            }

            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                String dt = ifd0.getString(ExifIFD0Directory.TAG_DATETIME);
                LocalDateTime parsed = tryParseExifDate(dt);
                if (parsed != null) return Optional.of(parsed);
            }

            for (Directory dir : metadata.getDirectories()) {
                for (var tag : dir.getTags()) {
                    String name = tag.getTagName().toLowerCase(Locale.ROOT);
                    if (name.contains("date") || name.contains("time")) {
                        LocalDateTime parsed = tryParseExifDate(tag.getDescription());
                        if (parsed != null) return Optional.of(parsed);
                    }
                }
            }

            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private LocalDateTime fallbackFileTime(Path filePath) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            FileTime ft = attrs.creationTime();
            if (ft == null || ft.toMillis() <= 0) ft = attrs.lastModifiedTime();
            if (ft == null || ft.toMillis() <= 0) return null;
            return Instant.ofEpochMilli(ft.toMillis()).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime tryParseExifDate(String value) {
        if (value == null) return null;
        String v = value.trim();
        try {
            if (v.length() >= 19) v = v.substring(0, 19);
            return LocalDateTime.parse(v, EXIF_FMT);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void write(Path src, Path dest, FilesystemMode mode) throws Exception {
        if (mode == FilesystemMode.MOVE) {
            Files.move(src, dest);
        } else {
            Files.copy(src, dest);
        }
    }

    private String getExtensionWithDot(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "";
        return filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String nextDatedFilename(Path targetDir, String dateStr, String ext, Map<String, Integer> counters) {
        String counterKey = targetDir.toAbsolutePath() + "|" + dateStr;
        int n = counters.getOrDefault(counterKey, 1);

        while (Files.exists(targetDir.resolve(dateStr + "." + n + ext))) {
            n++;
        }

        counters.put(counterKey, n + 1);
        return dateStr + "." + n + ext;
    }

    private Path uniquePath(Path path) {
        if (!Files.exists(path)) return path;

        String name = path.getFileName().toString();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }

        for (int i = 2; i < 10000; i++) {
            Path candidate = path.getParent().resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return path;
    }

    private String safeFilename(String original) {
        String name = (original == null || original.isBlank()) ? "file" : original;
        name = Paths.get(name).getFileName().toString();
        return name.replaceAll("[\\r\\n\\t\\\\/:*?\"<>|]", "_");
    }

    enum FilesystemMode { COPY, MOVE;
        static FilesystemMode from(String s) {
            if (s == null) return MOVE;
            String v = s.trim().toLowerCase(Locale.ROOT);
            return v.equals("copy") ? COPY : MOVE;
        }
    }
}
