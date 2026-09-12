package com.example.FileSorter;

import java.nio.file.*;

/** User-facing errors should not depend on the operating system's display language. */
final class SwedishMessages {
    private SwedishMessages() { }

    static String error(Exception exception) {
        if (exception instanceof InvalidPathException path) return "Ogiltig sökväg: " + path.getInput();
        if (exception instanceof FileSystemException file) {
            String path = file.getFile() == null ? "" : ": " + file.getFile();
            if (file instanceof NoSuchFileException) return "Filen eller mappen finns inte" + path;
            if (file instanceof AccessDeniedException) return "Åtkomst nekad" + path;
            if (file instanceof NotDirectoryException) return "Sökvägen är inte en mapp" + path;
            if (file instanceof FileAlreadyExistsException) return "Filen eller mappen finns redan" + path;
            if (file instanceof DirectoryNotEmptyException) return "Mappen är inte tom" + path;
            if (file instanceof FileSystemLoopException) return "En cirkulär mapplänk upptäcktes" + path;
            return "Filsystemet kunde inte utföra åtgärden" + path;
        }
        if (exception instanceof SecurityException) return "Programmet saknar behörighet att utföra åtgärden.";
        if (exception instanceof UnsupportedOperationException) return "Filsystemet stöder inte den här åtgärden.";
        return exception.getMessage() == null ? "Åtgärden kunde inte slutföras." : exception.getMessage();
    }
}
