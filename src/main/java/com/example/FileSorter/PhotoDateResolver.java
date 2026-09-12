package com.example.FileSorter;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.KeyValuePair;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import java.util.regex.*;

@Component
public class PhotoDateResolver {
    private static final Set<String> PHOTO_EXTENSIONS = Set.of(
            "jpg", "jpeg", "jpe", "jfif", "jif", "mpo", "png", "apng", "gif", "bmp", "dib",
            "tif", "tiff", "webp", "heic", "heif", "hif", "avif", "avifs", "heics", "heifs",
            "dng", "raw", "arw", "srf", "sr2", "crw", "cr2", "cr3", "nef", "nrw", "orf",
            "rw2", "rwl", "raf", "pef", "ptx", "srw", "x3f", "3fr", "fff", "iiq", "kdc",
            "dcr", "mos", "mrw", "erf", "mef", "mdc", "psd", "psb", "ico", "pcx", "tga",
            "jxl", "jp2", "j2k", "jpf", "jpx", "jpm", "jxr", "wdp", "hdp", "exr", "hdr",
            "ppm", "pgm", "pbm", "pnm", "qoi", "svg", "eps", "ai");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            // Phones, social-app exports, PCs and screen recordings.
            "mp4", "mov", "m4v", "mp4v", "mpeg4", "qt", "3gp", "3g2", "3gpp", "3gpp2", "3gp2",
            "avi", "divx", "xvid", "mkv", "webm", "wmv", "asf", "flv", "f4v",
            // MPEG, camcorders, DVD, recordings and action cameras.
            "mpg", "mpeg", "mpe", "m1v", "m2v", "mpv", "m2p", "mts", "m2ts", "m2t", "ts",
            "tp", "trp", "vob", "vro", "mod", "tod", "m2vts", "m2tse", "avchd", "bik", "bk2",
            "insv", "360", "lrv", "gxf", "mxf", "dv", "dif", "mjpg", "mjpeg", "mj2", "mjp2",
            // Other containers and elementary video streams. Many have no embedded capture date.
            "ogv", "ogm", "ogx", "ogg", "rm", "rmvb", "amv", "nsv", "nut", "wtv", "dvr-ms",
            "h264", "264", "h265", "265", "hevc", "h266", "266", "vvc", "av1", "ivf", "y4m");
    private static final Pattern NON_LETTERS = Pattern.compile("[^a-z]");
    private static final Pattern METADATA_DATE = Pattern.compile(
            "^(\\d{4})[-:](\\d{2})[-:](\\d{2})(?:$|[Tt \\t].*)", Pattern.DOTALL);
    private static final Pattern COMPACT_DATE = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
    private static final Pattern FILENAME_DATE = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})[-_ .](\\d{2})[-_ .](\\d{2})(?!\\d)"
                    + "|(?<!\\d)((?:19|20)\\d{2})(\\d{2})(\\d{2})(?=\\D|$|\\d{6}(?:\\D|$))");
    private static final List<String> DATE_TAGS = List.of(
            "datetimeoriginal", "comapplequicktimecreationdate", "shotdate", "datetimerecorded", "daterecorded", "recordeddate",
            "creationdate", "datetimecreated", "datecreated", "contentcreatedate", "createdate",
            "datetimedigitized", "digitalcreationdate", "mediacreatedate", "trackcreatedate", "creationtime", "dateutc");

    public record Result(LocalDate date, String source, String reason) {
        public boolean handled() { return date != null; }
    }

    public Result resolve(Path file, BasicFileAttributes attributes, boolean useFileDates,
                          ExifToolService.Session exifTool, FfprobeService.Session ffprobe) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean video = VIDEO_EXTENSIONS.contains(extension);
        if (!PHOTO_EXTENSIONS.contains(extension) && !video) {
            return new Result(null, "none", "Filtypen saknar stöd" + (extension.isEmpty() ? " (filändelse saknas)" : ": ." + extension));
        }
        if (attributes.size() == 0) return new Result(null, "none", "Tom fil");

        Result embedded = readBuiltIn(file);
        if (embedded != null) return embedded;
        Result external = readTags(exifTool.read(file), "ExifTool");
        if (external != null) return external;
        if (video) {
            Result videoDate = readTags(ffprobe.read(file), "ffprobe");
            if (videoDate != null) return videoDate;
        }

        // Recognize dated camera/phone/social exports. Arbitrary social-app IDs are not Unix timestamps.
        Set<LocalDate> dates = new LinkedHashSet<>();
        Matcher match = FILENAME_DATE.matcher(dot > 0 ? name.substring(0, dot) : name);
        while (match.find()) {
            int first = match.group(1) == null ? 4 : 1;
            LocalDate date = dateOf(match.group(first), match.group(first + 1), match.group(first + 2));
            if (date != null) dates.add(date);
        }
        if (dates.size() == 1) return new Result(dates.iterator().next(), "filename", "Datum i originalets filnamn");
        if (dates.size() > 1) return new Result(null, "none", "Filnamnet innehåller motstridiga datum");

        if (useFileDates) {
            // Modification time usually survives copies; Windows creation time usually does not.
            LocalDate modified = fileDate(attributes.lastModifiedTime().toInstant());
            if (modified != null) return new Result(modified, "file date", "Filsystemets datum för senaste ändring (valfri reservkälla)");
            LocalDate created = fileDate(attributes.creationTime().toInstant());
            if (created != null) return new Result(created, "file date", "Filsystemets skapandedatum (valfri reservkälla)");
        }
        return new Result(null, "none", video
                ? "Inget användbart inspelningsdatum, skapandedatum eller datum i filnamnet hittades. Exporterade videor kan sakna datummetadata (valfria läsare: ExifTool / ffprobe)"
                : "Inget användbart datum i metadata eller filnamn hittades. Formatet kan kräva ExifTool");
    }

    private Result readTags(Map<String, String> tags, String reader) {
        for (String wanted : DATE_TAGS) {
            for (var tag : tags.entrySet()) {
                if (!normalize(tag.getKey()).equals(wanted)) continue;
                LocalDate date = parseDate(tag.getValue());
                if (date != null && !isContainerEpoch(date, wanted)) {
                    return new Result(date, "metadata", reader + ": " + tag.getKey());
                }
            }
        }
        return null;
    }

    private Result readBuiltIn(Path file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
            for (ExifSubIFDDirectory exif : metadata.getDirectoriesOfType(ExifSubIFDDirectory.class)) {
                LocalDate date = parseDate(exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
                if (date != null) return new Result(date, "metadata", "EXIF DateTimeOriginal");
            }
            for (String wanted : DATE_TAGS) {
                for (Directory directory : metadata.getDirectories()) {
                    if (directory instanceof XmpDirectory xmp) {
                        for (var property : xmp.getXmpProperties().entrySet()) {
                            if (normalize(property.getKey()).equals(wanted)) {
                                LocalDate date = parseDate(property.getValue());
                                if (date != null) return new Result(date, "metadata", "XMP " + property.getKey());
                            }
                        }
                    }
                    // An explicit allowlist avoids modification dates, GPS dates and unrelated timestamps.
                    for (var tag : directory.getTags()) {
                        if (!normalize(tag.getTagName()).equals(wanted)) continue;
                        Object value = directory.getObject(tag.getTagType());
                        LocalDate date = value instanceof Date timestamp
                                ? timestamp.toInstant().atZone(ZoneOffset.UTC).toLocalDate()
                                : parseDate(directory.getString(tag.getTagType()));
                        if (usable(date) && !isContainerEpoch(date, wanted)) {
                            return new Result(date, "metadata", directory.getName() + ": " + tag.getTagName());
                        }
                    }
                }
            }
            for (PngDirectory png : metadata.getDirectoriesOfType(PngDirectory.class)) {
                Object text = png.getObject(PngDirectory.TAG_TEXTUAL_DATA);
                if (text instanceof Iterable<?> entries) {
                    for (Object entry : entries) {
                        if (entry instanceof KeyValuePair pair && DATE_TAGS.contains(normalize(pair.getKey()))) {
                            LocalDate date = parseDate(pair.getValue().toString());
                            if (date != null) return new Result(date, "metadata", "PNG " + pair.getKey());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Metadata failure never prevents trying ExifTool or the original filename.
        }
        return null;
    }

    private static String normalize(String tag) {
        int namespace = tag.lastIndexOf(':');
        return NON_LETTERS.matcher(tag.substring(namespace + 1).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    static LocalDate parseDate(String text) {
        if (text == null) return null;
        String value = text.replace("\u0000", "").trim();
        Matcher match = METADATA_DATE.matcher(value);
        if (!match.matches()) match = COMPACT_DATE.matcher(value);
        return match.matches() ? dateOf(match.group(1), match.group(2), match.group(3)) : null;
    }

    private static LocalDate dateOf(String year, String month, String day) {
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
            return usable(date) ? date : null;
        } catch (DateTimeException | NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean usable(LocalDate date) {
        return date != null && date.getYear() >= 1800 && !date.isAfter(LocalDate.now().plusDays(1));
    }

    private static boolean isContainerEpoch(LocalDate date, String tag) {
        return (tag.equals("creationtime") || tag.equals("createdate") || tag.equals("mediacreatedate")
                || tag.equals("trackcreatedate") || tag.equals("dateutc"))
                && (date.equals(LocalDate.of(1904, 1, 1)) || date.equals(LocalDate.of(1970, 1, 1)));
    }

    private static LocalDate fileDate(Instant instant) {
        if (instant.getEpochSecond() <= 0) return null;
        LocalDate date = instant.atZone(ZoneId.systemDefault()).toLocalDate();
        return usable(date) ? date : null;
    }
}
