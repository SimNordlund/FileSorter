# File Sorter

A local Java / Spring Boot application that organizes a folder of photos and videos into `YYYY-MM` folders. The browser interface is in Swedish. Select a folder or paste its full path. There are no uploads, accounts, API keys, or AI services.

## Start the application

Use a **JDK 17** installation. Open this project as a Gradle project in your IDE and run `com.example.FileSorter.DemoApplication`, or run this from the project folder in PowerShell:

```powershell
.\gradlew.bat bootRun
```

Then open **[http://localhost:8080](http://localhost:8080)**. The first Gradle invocation needs internet access to download the wrapper distribution and dependencies. Sorting itself runs locally. Stop the application from your IDE or with Ctrl+C in the terminal.

The Gradle wrapper uses 8.10.2, matching the existing Spring Boot 3.3 project's [supported Gradle major versions](https://docs.spring.io/spring-boot/3.3/system-requirements.html). No build, test run, or application launch was performed as part of this rewrite, per the repository instructions.

## Organize a collection

1. Click **Bläddra…** and navigate to your photo/video folder, or paste an absolute path such as `C:\Users\YourName\Pictures\Camera imports`. The browser lists folders on the computer running the Java application, including accessible drives. Large folders are read directly from disk.
2. Leave **Ta med alla undermappar** on to process a whole collection.
3. Choose **Kopiera bilder och videor** (default) or **Flytta bilder och videor**. Move mode requires acknowledging that the originals will be removed after verified copying.
4. Optionally choose an output folder under **Målmapp och datuminställningar**. You can type a new folder path. By default the application uses `SortedPictures` inside the selected folder.
5. Click **Förhandsgranska** to see the month groups, undated files, date sources and errors without creating any directories, reports or output files.
6. Click **Kopiera och sortera** or **Flytta och sortera** to perform the operation. A preview is optional. Sorting reads the source again; the preview is not a locked snapshot.

For example:

```text
Camera imports/
  SortedPictures/
    2026-09/
      20260905.HEIC
      20260905.png
      202609051.png
    2026-08/
      20260818.mp4
    EjHanterade/
      undated-picture.jpg
      undated-video.mp4
      unsupported-file.xyz
    file-sorter-report-<job-id>.csv
```

The output must be a subfolder of the source or a separate location. Selecting the source itself or one of its parents as output is rejected. The selected output subtree is excluded from scanning, so the sorter does not sort its own newly created files.

## How dates are chosen

The date reader tries the following sources in order:

1. **Embedded metadata**, starting with EXIF `DateTimeOriginal`, then an explicit list of creation/capture tags in XMP, IPTC, PNG text, QuickTime, MP4 and other supported metadata directories. If the built-in reader finds no usable date, optional ExifTool is tried, followed by optional **ffprobe for videos**. Video metadata includes Apple QuickTime creation tags, recording/shot dates, Matroska date fields, and creation dates in the container and video streams.
2. **The original filename**, recognizing dates such as `IMG_20260905_142012.jpg`, `20260905142012.jpg`, `IMG-20260905-WA0001.jpg`, `Screenshot 2026-09-05 142012.png` and `2026_09_05.jpg`.
3. **Filesystem dates, only if enabled**: last-modified time, then creation time. These are less reliable capture dates because edits, downloads and file copying can change them.
4. Otherwise, the file goes to **`EjHanterade`**.

Invalid dates, dates before 1800, dates beyond tomorrow, zero/epoch placeholders in container creation fields, and ambiguous filenames with different valid dates are rejected. Ambiguous filenames go to `EjHanterade` even with filesystem fallback enabled. Generic EXIF modification dates and PNG modification times are deliberately not treated as capture dates. An undated Snipping Tool image such as `Capture.png` therefore goes to `EjHanterade` unless you enable filesystem dates.

Calendar dates in textual metadata are preserved as recorded, without applying timezone shifts. Numeric QuickTime/MP4 timestamps use their UTC calendar date. The reader does not guess the timezone of an old camera. For recordings near midnight or month boundaries, a missing recording timezone can affect the month; the report identifies the metadata tag used. See ExifTool's [QuickTime date documentation](https://exiftool.org/TagNames/QuickTime.html).

An incorrect but plausible date already embedded by a camera cannot reliably be corrected automatically. AI is not used to guess when a photo or video was captured.

## Formats and optional metadata readers

The built-in [metadata-extractor library](https://github.com/drewnoakes/metadata-extractor) reads metadata from JPEG, TIFF, PNG, WebP, GIF, BMP, HEIF/HEIC/AVIF, PSD, several camera RAW formats, QuickTime/MOV, MP4 and others. Support means reading available metadata, not decoding or converting the picture.

The sorter recognizes over 100 photo/video extensions, including Apple and Samsung formats, RAW variants, MPG/MPEG, AVI, MKV, MTS/M2TS, WebM, JPEG XL and JPEG 2000. Some require ExifTool or ffprobe to read their embedded dates. Recognized media can still be sorted by a date in its filename (or optional filesystem dates) even when its metadata format is not readable. This is not a file-integrity or corruption checker. Empty files, unknown extensions, and files with no usable date go to `EjHanterade`. Other regular files in the selected folder, including sidecars and documents, also go there; their contents are not interpreted as photo metadata.

### Videos from phones, PCs and social apps

Saved/exported videos from Samsung and other Android phones, iPhones, PCs, screen recorders, Snapchat and Instagram use the same sorting flow as photos. They can share one input folder and the same monthly output folders. Live Photo companion MOV files are processed as videos using their own dates.

Recognized video extensions (case-insensitive):

```text
mp4 mov m4v mp4v mpeg4 qt 3gp 3g2 3gpp 3gpp2 3gp2
avi divx xvid mkv webm wmv asf flv f4v
mpg mpeg mpe m1v m2v mpv m2p mts m2ts m2t ts
tp trp vob vro mod tod m2vts m2tse avchd bik bk2
insv 360 lrv gxf mxf dv dif mjpg mjpeg mj2 mjp2
ogv ogm ogx ogg rm rmvb amv nsv nut wtv dvr-ms
h264 264 h265 265 hevc h266 266 vvc av1 ivf y4m
```

These are recognized file extensions, not a guarantee that every container or codec has readable date metadata. H.264, HEVC/H.265 and AV1 videos keep their existing encoding; sorting never transcodes them. Reader support for uncommon formats depends on the installed ExifTool/FFmpeg version. Elementary streams often need a filename date because they have no container creation metadata.

Filename examples such as `VID_20260905_142012.mp4`, `PXL_20260905_142012345.mp4`, `Screen Recording 2026-09-05.mov` and `Instagram_2026-09-05.mp4` can supply a date when metadata is missing. A saved file such as `Snapchat-123456789.mp4` with no date metadata goes to **`EjHanterade`** by default. Random numeric IDs are not interpreted as Unix timestamps. Optional filesystem-date fallback remains off by default for both photos and videos.

### ExifTool

For the widest metadata coverage, install **[ExifTool from its official website](https://exiftool.org/)**:

1. On Windows, extract the distribution and rename `exiftool(-k).exe` to `exiftool.exe`. Keep the accompanying `exiftool_files` directory beside the executable if supplied by the distribution. See the [official installation instructions](https://exiftool.org/install.html).
2. Add its directory to `PATH`, or set the `EXIFTOOL_PATH` environment variable to the full executable path before starting the application:

   ```powershell
   $env:EXIFTOOL_PATH = 'C:\Tools\ExifTool\exiftool.exe'
   .\gradlew.bat bootRun
   ```

   Alternatively, set `filesorter.exiftool-path=C:/Tools/ExifTool/exiftool.exe` in `src/main/resources/application.properties`. Use forward slashes in that properties file.
3. Restart the application. The interface displays whether the executable was found. A detected executable that cannot actually run produces a warning when first used.

ExifTool is optional and is never downloaded or installed automatically. It is invoked with read-only options in one persistent process per job. A timeout (20 seconds by default) or process failure disables it for the rest of that job; video files can still try ffprobe, followed by filename rules. Adjust `filesorter.exiftool-timeout-seconds` if necessary. A format with no stored date still has no capture date, even with ExifTool.

### ffprobe (additional video metadata support)

For additional video container support, install an FFmpeg distribution containing **ffprobe**, using the platform links on the [official FFmpeg download page](https://ffmpeg.org/download.html). [ffprobe](https://ffmpeg.org/ffprobe.html) reads container and stream metadata; it does not need to convert the video to return those tags.

Put the directory containing `ffprobe.exe` on `PATH`, or set its full path before starting the application:

```powershell
$env:FFPROBE_PATH = 'C:\Tools\ffmpeg\bin\ffprobe.exe'
.\gradlew.bat bootRun
```

Alternatively, set `filesorter.ffprobe-path=C:/Tools/ffmpeg/bin/ffprobe.exe` in `src/main/resources/application.properties`. Restart the application after configuration. The interface shows detection of both optional readers separately.

ffprobe is used only for recognized video extensions whose dates were not found by the preceding readers. It reads video-stream and container tags using JSON output, with network protocols disabled, bounded output and a configurable timeout (`filesorter.ffprobe-timeout-seconds`, default 20). Cancellation interrupts a pending probe. A failed individual probe can still fall back to filename dates; a timeout or process failure disables ffprobe for the rest of that job and produces a warning. It is optional, and nothing is automatically installed. Neither reader can recover a missing capture date from the visual content of a video.

## File handling and large collections

- **Dated files are renamed to `YYYYMMDD`, keeping their original extension.** For example, `20260601.jpg`, then `202606011.jpg`, `202606012.jpg`, and so on when different files occupy the previous names. Numbering is based on occupied names in the target month folder for that extension; existing files are never overwritten. Preview uses the same naming rules. Files without a usable date keep their original names in `EjHanterade`, using suffixes such as `undated-picture (2).jpg` for collisions. No resizing, recompression or metadata writing occurs, and originals retain their names in copy mode.
- **Existing identical destinations are skipped** after comparing their full contents, including numbered collision destinations. The source is kept in both copy and move mode when this happens. This avoids duplicates on repeat runs with the same output and date settings. Dated files with different original names can be deduplicated if they resolve to the same date and extension; this is not collection-wide duplicate detection.
- **Copies are streamed and byte-verified.** Temporary output copies are completed and compared before final placement. Move mode deletes the source only after the verified destination exists and the source attributes are checked again. Original last-modified time is preserved where the filesystem permits; creation time, permissions and other filesystem attributes are not copied. Embedded metadata is unchanged.
- **Only one job runs at a time.** There is no browser upload size limit. The folder tree is traversed incrementally, and the interface shows ongoing file counts instead of requiring a full pre-scan. Keep the source stable while a job runs. Available disk space, filesystem permissions and path limits still apply; copy mode needs enough space for a second collection, and move mode needs temporary space for the current file.
- **Cancellation keeps completed work.** It interrupts streaming copy/verification between chunks. Metadata extraction may need to finish or reach its ExifTool timeout first. There is no automatic rollback. Empty source directories are left in place after moves.
- **Unreadable files stay in the source.** Missing dates or unsupported formats route to `EjHanterade`; files that cannot be accessed, copied or removed are reported as errors. A verified destination copy is kept if removing the source fails.
- **Links and special files are skipped.** The traversal does not follow symlinks or nested folder junctions. Output folders that redirect through a link or junction during sorting are rejected.
- **Each actual run writes a UTF-8 CSV report** in the output root, with each processed/skipped file's source, destination, outcome, date source and reason. Errors are included. The interface retains the latest 80 file details and the first 100 error messages; the CSV is the full record. Preview name reservations use memory proportional to the number of planned destination paths, not file sizes.
- **Refresh is supported while the server stays running.** The interface reconnects to the latest job. Job state is not persisted across server restarts, but reports and completed files remain. After a crash, an incomplete `.file-sorter-*.part` temporary file may remain in an output month folder; preview again before restarting work.

## Local API

The application binds to `127.0.0.1:8080`. API requests use the `X-FileSorter-Token` header returned by `GET /api/config`; the UI supplies it automatically. Cross-site requests, unexpected hostnames and framing are rejected. This is a single-user local application, not a service to expose to a network.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/config` | Local folder roots, home path, API token, ExifTool and ffprobe detection |
| `GET /api/folders?path=...&filter=...` | Browse subfolders (up to 1,000 matches; filter larger listings) |
| `POST /api/jobs` | Start a preview or an actual operation |
| `GET /api/jobs/current` | Latest job, or `{"job":null}` when none exists |
| `GET /api/jobs/{id}` | Status of the current job |
| `POST /api/jobs/{id}/cancel` | Request cancellation |

Example request body:

```json
{
  "sourceDir": "C:\\Pictures\\Camera imports",
  "outputDir": "",
  "mode": "COPY",
  "recursive": true,
  "useFileDates": false,
  "preview": true
}
```

Omitted options default to copy, recursive traversal, no filesystem date fallback, and preview. The old synchronous `/api/photos/organize-local` endpoint has been replaced by the job API; there is no implicit Desktop source or default destructive operation.
