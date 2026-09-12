package com.example.FileSorter;

public record OrganizeRequest(
        String sourceDir,
        String outputDir,
        Mode mode,
        Boolean recursive,
        Boolean useFileDates,
        Boolean preview
) {
    public enum Mode { COPY, MOVE }

    public Mode effectiveMode() { return mode == null ? Mode.COPY : mode; }
    public boolean includeSubfolders() { return recursive == null || recursive; }
    public boolean allowFileDates() { return Boolean.TRUE.equals(useFileDates); }
    public boolean isPreview() { return preview == null || preview; }
}
