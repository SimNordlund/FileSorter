package com.example.FileSorter;

import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/photos")
public class PhotoController {

    private final PhotoOrganizerService service;

    public PhotoController(PhotoOrganizerService service) {
        this.service = service;
    }

    @PostMapping("/organize-local")
    public Map<String, Object> organizeLocal(
                                              @RequestParam(value = "sourceDir", required = false) String sourceDir,
                                              @RequestParam(value = "baseDir", required = false) String baseDir,
                                              @RequestParam(value = "mode", defaultValue = "move") String mode,
                                              @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun
    ) {
        Path src = (sourceDir == null || sourceDir.isBlank())
                ? PhotoOrganizerService.defaultDesktopDir()
                : Path.of(sourceDir);

        Path base = (baseDir == null || baseDir.isBlank())
                ? src
                : Path.of(baseDir);

        return service.organizeDirectory(src, base, mode, dryRun);
    }
}
