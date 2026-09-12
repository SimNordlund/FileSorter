'use strict';

const $ = id => document.getElementById(id);
let config;
let currentJob;
let pollTimer;
let starting = false;
let folderTarget = 'source';
let folderLocation;
let folderParent;
let folderRequest = 0;
let filterTimer;
const number = value => Number(value).toLocaleString();
const basename = path => path.split(/[\\/]/).filter(Boolean).pop() || path;

async function api(path, body, retry = true) {
  const response = await fetch(path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'X-FileSorter-Token': config?.token || '', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30000)
  });
  if (response.status === 403 && retry && path !== '/api/config') {
    config = await api('/api/config', undefined, false);
    return api(path, body, false);
  }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.message || `Request failed (${response.status}). Refresh the page to reconnect.`);
  return result;
}

function showError(message) {
  $('error-banner').textContent = message;
  $('error-banner').hidden = !message;
}

function updateMode() {
  const move = document.querySelector('input[name=mode]:checked').value === 'MOVE';
  $('move-acknowledgement').hidden = !move;
  $('organize').textContent = move ? 'Move & organize →' : 'Copy & organize →';
  $('organize').disabled = move && !$('acknowledge-move').checked;
}

function settings(preview) {
  return {
    sourceDir: $('source').value.trim(), outputDir: $('output').value.trim(),
    mode: document.querySelector('input[name=mode]:checked').value,
    recursive: $('recursive').checked, useFileDates: $('file-dates').checked, preview
  };
}

async function start(preview) {
  if (starting || currentJob?.status === 'RUNNING' || !$('sort-form').reportValidity()) return;
  const request = settings(preview);
  if (!preview && request.mode === 'MOVE' && !$('acknowledge-move').checked) return;
  showError('');
  starting = true;
  $('settings').disabled = true;
  try {
    const job = await api('/api/jobs', request);
    render(job);
    $('results').scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' });
    schedulePoll();
  } catch (error) {
    // A lost HTTP response does not imply the server failed to start the job.
    showError(error.message);
    try {
      const state = await api('/api/jobs/current');
      if (state.job) { restoreSettings(state.job); render(state.job); schedulePoll(); }
      else $('settings').disabled = false;
    } catch {
      showError('Connection lost. A job may have started. Reconnecting before allowing another operation…');
      schedulePoll();
    }
  } finally {
    starting = false;
  }
}

function schedulePoll() {
  clearTimeout(pollTimer);
  pollTimer = setTimeout(poll, 1000);
}

async function poll() {
  try {
    const state = await api('/api/jobs/current');
    showError('');
    if (!state.job) {
      if (currentJob?.status === 'RUNNING') showError('The server restarted. The previous job may have partial output. Check its CSV report and preview again.');
      currentJob = null;
      $('settings').disabled = false;
      $('cancel').hidden = true;
      $('activity').hidden = true;
      $('current-file').textContent = '';
      if (!$('results').hidden) $('results-heading').textContent = 'Job no longer available';
      return;
    }
    if (currentJob?.id !== state.job.id) restoreSettings(state.job);
    render(state.job);
    if (state.job.status === 'RUNNING') schedulePoll();
  } catch (error) {
    showError(`Connection interrupted. The sorter may still be working. Retrying… ${error.message}`);
    $('settings').disabled = true;
    pollTimer = setTimeout(poll, 3000);
  }
}

function restoreSettings(job) {
  $('source').value = job.sourceDir;
  $('output').value = job.outputDir;
  document.querySelector(`input[name=mode][value="${job.mode === 'MOVE' ? 'MOVE' : 'COPY'}"]`).checked = true;
  $('recursive').checked = job.recursive;
  $('file-dates').checked = job.useFileDates;
  $('acknowledge-move').checked = false;
  updateMode();
}

function showMessages(id, messages) {
  const container = $(id);
  container.replaceChildren();
  container.hidden = !messages.length;
  if (!messages.length) return;
  const list = document.createElement('ul');
  for (const message of messages) {
    const item = document.createElement('li');
    item.textContent = message;
    list.append(item);
  }
  container.append(list);
}

function render(job) {
  currentJob = job;
  const running = job.status === 'RUNNING';
  const verb = job.mode === 'MOVE' ? 'Moving' : 'Copying';
  $('settings').disabled = running;
  $('results').hidden = false;
  $('cancel').hidden = !running;
  $('cancel').disabled = job.cancelRequested;
  $('cancel').textContent = job.cancelRequested ? 'Cancelling…' : 'Cancel';
  $('activity').hidden = !running;
  $('job-kind').textContent = job.preview ? 'PREVIEW · NO FILES CHANGED' : `${job.mode} · YOUR COLLECTION`;
  const titles = { COMPLETED: job.preview ? 'Your preview is ready' : 'Your collection is organized',
    COMPLETED_WITH_ERRORS: job.preview ? 'Preview finished with errors' : 'Finished with errors',
    CANCELLED: 'Job cancelled', FAILED: 'The job stopped' };
  const heading = running ? (job.cancelRequested ? 'Finishing the current operation…' : job.preview ? 'Finding a home for every file…' : `${verb} and organizing…`) : titles[job.status] || job.status;
  if ($('results-heading').textContent !== heading) $('results-heading').textContent = heading;
  $('job-description').textContent = `${job.recursive ? 'Including subfolders' : 'Top-level files only'} · Original filenames preserved · ${job.useFileDates ? 'Filesystem date fallback enabled' : 'Embedded and filename dates only'}`;
  $('current-file').textContent = job.currentFile ? `Reading / processing: ${job.currentFile}` : '';
  for (const [id, key] of [['processed', 'processed'], ['dated', 'dated'], ['unhandled', 'unhandled'], ['already-present', 'alreadyPresent'], ['errors', 'errors']]) $(id).textContent = number(job[key]);
  $('dated-label').textContent = job.preview ? 'Would sort by date' : 'Sorted by date';
  $('output-path').textContent = `Output: ${job.outputDir}`;
  $('report-path').hidden = !job.report;
  $('report-path').textContent = job.report ? `Full CSV report: ${job.report}` : '';
  $('date-sources').textContent = Object.entries(job.dateSources).map(([key, count]) => `${key === 'none' ? 'No usable date / unsupported' : key}: ${number(count)}`).join(' · ') + (job.skipped ? ` · Skipped links / special files: ${number(job.skipped)}` : '');
  const note = $('completion-note');
  note.hidden = running;
  note.textContent = job.preview && (job.status === 'CANCELLED' || job.status === 'FAILED')
    ? 'No files were changed. This preview is incomplete; only files processed before it stopped are shown.'
    : job.preview && job.processed === 0 ? 'No files were changed. No files were found in the selected scope; try including subfolders or choosing a different folder.'
    : job.preview ? 'No files were changed. Review the destinations below, then use the organize button above. Sorting reads the folder again, so changes made after the preview will be included.'
    : job.status === 'CANCELLED' || job.status === 'FAILED' ? 'Completed files remain in the output folder. Files not yet processed remain in the source. Review the report before starting again.'
    : job.errors ? 'Some files could not be processed. Review the errors and CSV report. Files that could not be read or written remain at their source.'
    : job.processed === 0 ? 'No files were found in the selected scope. Try including subfolders or choosing a different folder.'
    : 'Finished. Identical files already in the destination were skipped, with their source copies kept.';
  showMessages('warnings', job.warnings);
  showMessages('job-errors', job.errorMessages);
  $('group-list').replaceChildren();
  for (const [group, count] of Object.entries(job.groups)) {
    const chip = document.createElement('div');
    chip.className = 'group-chip';
    const label = document.createElement('span');
    label.textContent = group;
    const total = document.createElement('strong');
    total.textContent = number(count);
    chip.append(label, total);
    $('group-list').append(chip);
  }
  $('file-list').replaceChildren();
  for (const sample of [...job.samples].reverse()) {
    const row = document.createElement('tr');
    for (const value of [basename(sample.source), sample.destination || '—', sample.dateSource, sample.result]) {
      const cell = document.createElement('td');
      cell.textContent = value;
      row.append(cell);
    }
    row.firstChild.title = sample.source;
    const reason = document.createElement('small');
    reason.textContent = sample.reason;
    row.lastChild.append(reason);
    $('file-list').append(row);
  }
}

async function browse(target) {
  folderTarget = target;
  folderLocation = null;
  $('folder-title').textContent = target === 'source' ? 'Choose your photo and video folder' : 'Choose an output folder';
  $('folder-filter').value = '';
  $('folder-dialog').showModal();
  await loadFolders($(target).value.trim() || config.home);
}

async function loadFolders(path, filter = '') {
  const request = ++folderRequest;
  $('use-folder').disabled = true;
  $('folder-error').hidden = true;
  $('folder-note').textContent = 'Reading folders…';
  $('folder-list').replaceChildren();
  try {
    const listing = await api(`/api/folders?path=${encodeURIComponent(path)}&filter=${encodeURIComponent(filter)}`);
    if (request !== folderRequest || !$('folder-dialog').open) return;
    folderLocation = listing.path;
    folderParent = listing.parent;
    $('folder-path').value = listing.path;
    $('folder-up').disabled = !listing.parent;
    $('use-folder').disabled = false;
    $('folder-note').textContent = listing.truncated ? 'Showing the first 1,000 matching folders. Use the filter to narrow the list.' : listing.folders.length ? 'Open a subfolder, or select the current folder below.' : 'No matching subfolders. You can select this folder.';
    for (const folder of listing.folders) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'folder-row';
      const icon = document.createElement('span');
      icon.className = 'folder-icon';
      icon.setAttribute('aria-hidden', 'true');
      const label = document.createElement('span');
      label.textContent = folder.name;
      button.append(icon, label);
      button.addEventListener('click', () => navigateFolder(folder.path));
      $('folder-list').append(button);
    }
  } catch (error) {
    if (request !== folderRequest || !$('folder-dialog').open) return;
    $('folder-error').textContent = `Cannot open this folder. ${error.message}`;
    $('folder-error').hidden = false;
    $('folder-note').textContent = 'Enter an existing folder path or choose Home / a drive above.';
  }
}

function navigateFolder(path) {
  clearTimeout(filterTimer);
  $('folder-filter').value = '';
  $('folder-path').value = path;
  loadFolders(path);
}

$('sort-form').addEventListener('submit', event => { event.preventDefault(); start(true); });
$('preview').addEventListener('click', () => start(true));
$('organize').addEventListener('click', () => start(false));
document.querySelectorAll('input[name=mode]').forEach(input => input.addEventListener('change', updateMode));
$('acknowledge-move').addEventListener('change', updateMode);
$('browse-source').addEventListener('click', () => browse('source'));
$('browse-output').addEventListener('click', () => browse('output'));
$('cancel').addEventListener('click', async () => {
  if (!currentJob) return;
  $('cancel').disabled = true;
  try { render(await api(`/api/jobs/${currentJob.id}/cancel`, {})); schedulePoll(); }
  catch (error) { showError(error.message); schedulePoll(); }
});
for (const id of ['close-folder', 'cancel-folder']) $(id).addEventListener('click', () => $('folder-dialog').close());
$('folder-dialog').addEventListener('close', () => { folderRequest++; clearTimeout(filterTimer); });
$('folder-go').addEventListener('click', () => navigateFolder($('folder-path').value.trim()));
$('folder-path').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); navigateFolder($('folder-path').value.trim()); } });
$('folder-path').addEventListener('input', () => { $('use-folder').disabled = true; });
$('folder-up').addEventListener('click', () => { if (folderParent) navigateFolder(folderParent); });
$('folder-filter').addEventListener('input', () => {
  clearTimeout(filterTimer);
  if (folderLocation) filterTimer = setTimeout(() => loadFolders(folderLocation, $('folder-filter').value), 250);
});
$('use-folder').addEventListener('click', () => {
  if (!folderLocation) return;
  $(folderTarget).value = folderLocation;
  $('folder-dialog').close();
});

async function initialize() {
  try {
    config = await api('/api/config');
    const readers = ['Built-in metadata reader'];
    if (config.exifToolAvailable) readers.push('ExifTool detected');
    if (config.ffprobeAvailable) readers.push('ffprobe detected');
    const missing = [!config.exifToolAvailable && 'ExifTool', !config.ffprobeAvailable && 'ffprobe'].filter(Boolean);
    $('metadata-status').textContent = readers.join(' + ') + '.'
      + (missing.length ? ` Optional ${missing.join(' / ')} adds more metadata support (see README).` : '');
    for (const shortcut of [{ label: 'Home', path: config.home }, ...config.roots.map(path => ({ label: path, path }))]) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'button secondary';
      button.textContent = shortcut.label;
      button.addEventListener('click', () => navigateFolder(shortcut.path));
      $('folder-shortcuts').append(button);
    }
    const state = await api('/api/jobs/current');
    $('settings').disabled = false;
    if (state.job) {
      restoreSettings(state.job);
      render(state.job);
      if (state.job.status === 'RUNNING') schedulePoll();
    }
  } catch (error) {
    showError(`Cannot connect to File Sorter. Start the Java application, then refresh this page. ${error.message}`);
  }
}

initialize();
