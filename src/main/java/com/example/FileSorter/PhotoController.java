package com.example.FileSorter;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class PhotoController {
    private final PhotoOrganizerService organizer;
    private final ExifToolService exifTool;
    private final LocalAccessFilter access;

    public PhotoController(PhotoOrganizerService organizer, ExifToolService exifTool, LocalAccessFilter access) {
        this.organizer = organizer;
        this.exifTool = exifTool;
        this.access = access;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        List<String> roots = new ArrayList<>();
        FileSystems.getDefault().getRootDirectories().forEach(root -> roots.add(root.toString()));
        return Map.of("token", access.token(), "home", System.getProperty("user.home"), "roots", roots,
                "exifToolAvailable", exifTool.available(), "unhandledFolder", PhotoOrganizerService.UNHANDLED_FOLDER);
    }

    public record Folder(String name, String path) { }
    public record FolderListing(String path, String parent, List<Folder> folders, boolean truncated) { }

    @GetMapping("/folders")
    public FolderListing folders(@RequestParam(required = false) String path,
                                 @RequestParam(defaultValue = "") String filter) throws IOException {
        Path directory = path == null || path.isBlank() ? Path.of(System.getProperty("user.home")) : Path.of(path);
        directory = directory.toRealPath();
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("That path is not a folder.");
        List<Folder> children = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)
                        || !entry.getFileName().toString().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) continue;
                if (children.size() == 1000) { truncated = true; break; }
                children.add(new Folder(entry.getFileName().toString(), entry.toString()));
            }
        }
        children.sort(Comparator.comparing(Folder::name, String.CASE_INSENSITIVE_ORDER));
        Path parent = directory.getParent();
        return new FolderListing(directory.toString(), parent == null ? null : parent.toString(), children, truncated);
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PhotoOrganizerService.Snapshot start(@RequestBody OrganizeRequest request) throws IOException {
        return organizer.start(request);
    }

    @GetMapping("/jobs/current")
    public Map<String, Object> current() {
        Map<String, Object> response = new HashMap<>();
        response.put("job", organizer.current());
        return response;
    }

    @GetMapping("/jobs/{id}")
    public PhotoOrganizerService.Snapshot job(@PathVariable String id) { return organizer.get(id); }

    @PostMapping("/jobs/{id}/cancel")
    public PhotoOrganizerService.Snapshot cancel(@PathVariable String id) { return organizer.cancel(id); }
}
