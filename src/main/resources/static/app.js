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
const number = value => Number(value).toLocaleString('sv-SE');
const basename = path => path.split(/[\\/]/).filter(Boolean).pop() || path;
const dateSourceLabel = source => ({ metadata: 'Metadata', filename: 'Filnamn', 'file date': 'Filsystemets datum', none: 'Saknar datum / saknar stöd' })[source] || 'Okänd datumkälla';
const resultLabel = result => ({ preview: 'Förhandsgranskning', 'duplicate (preview)': 'Dubblett (förhandsgranskning)', 'already present': 'Finns redan', moved: 'Flyttad', copied: 'Kopierad', error: 'Fel', skipped: 'Överhoppad' })[result] || 'Okänt resultat';

async function api(path, body, retry = true) {
  let response;
  try {
    response = await fetch(path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { 'X-FileSorter-Token': config?.token || '', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(30000)
    });
  } catch (error) {
    throw new Error(error.name === 'TimeoutError' || error.name === 'AbortError'
      ? 'Servern svarade inte i tid. Försök igen.'
      : 'Det gick inte att ansluta till servern. Kontrollera att programmet körs.');
  }
  if (response.status === 403 && retry && path !== '/api/config') {
    config = await api('/api/config', undefined, false);
    return api(path, body, false);
  }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.message || `Begäran misslyckades (${response.status}). Ladda om sidan för att ansluta igen.`);
  return result;
}

function showError(message) {
  $('error-banner').textContent = message;
  $('error-banner').hidden = !message;
}

function updateMode() {
  const move = document.querySelector('input[name=mode]:checked').value === 'MOVE';
  $('move-acknowledgement').hidden = !move;
  $('organize').textContent = move ? 'Flytta och sortera →' : 'Kopiera och sortera →';
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
  if (starting || currentJob?.status === 'RUNNING') return;
  $('source').setCustomValidity($('source').value.trim() ? '' : 'Välj en mapp eller ange dess fullständiga sökväg.');
  if (!$('sort-form').reportValidity()) return;
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
      showError('Anslutningen bröts. En sortering kan ha startat. Ansluter igen innan en ny åtgärd kan påbörjas…');
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
      if (currentJob?.status === 'RUNNING') showError('Servern har startats om. Vissa filer kan redan ha sorterats. Kontrollera CSV-rapporten och förhandsgranska igen.');
      currentJob = null;
      $('settings').disabled = false;
      $('cancel').hidden = true;
      $('activity').hidden = true;
      $('current-file').textContent = '';
      if (!$('results').hidden) $('results-heading').textContent = 'Körningen finns inte längre tillgänglig';
      return;
    }
    if (currentJob?.id !== state.job.id) restoreSettings(state.job);
    render(state.job);
    if (state.job.status === 'RUNNING') schedulePoll();
  } catch (error) {
    showError(`Anslutningen bröts. Sorteringen kan fortfarande pågå. Försöker igen… ${error.message}`);
    $('settings').disabled = true;
    pollTimer = setTimeout(poll, 3000);
  }
}

function restoreSettings(job) {
  $('source').value = job.sourceDir;
  $('source').setCustomValidity('');
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
  const verb = job.mode === 'MOVE' ? 'Flyttar' : 'Kopierar';
  $('settings').disabled = running;
  $('results').hidden = false;
  $('cancel').hidden = !running;
  $('cancel').disabled = job.cancelRequested;
  $('cancel').textContent = job.cancelRequested ? 'Avbryter…' : 'Avbryt';
  $('activity').hidden = !running;
  $('job-kind').textContent = job.preview ? 'FÖRHANDSGRANSKNING · INGA FILER ÄNDRADE' : `${job.mode === 'MOVE' ? 'FLYTTNING' : 'KOPIERING'} · DIN SAMLING`;
  const titles = { COMPLETED: job.preview ? 'Förhandsgranskningen är klar' : 'Din samling är sorterad',
    COMPLETED_WITH_ERRORS: job.preview ? 'Förhandsgranskningen är klar med fel' : 'Sorteringen är klar med fel',
    CANCELLED: 'Körningen avbröts', FAILED: 'Körningen stoppades' };
  const heading = running ? (job.cancelRequested ? 'Avslutar den pågående åtgärden…' : job.preview ? 'Hittar rätt mapp för varje fil…' : `${verb} och sorterar…`) : titles[job.status] || 'Okänd status';
  if ($('results-heading').textContent !== heading) $('results-heading').textContent = heading;
  $('job-description').textContent = `${job.recursive ? 'Undermappar ingår' : 'Endast filer direkt i vald mapp'} · Originalens filnamn bevaras · ${job.useFileDates ? 'Filsystemets datum används vid behov' : 'Endast datum i metadata och filnamn'}`;
  $('current-file').textContent = job.currentFile ? `Läser / bearbetar: ${job.currentFile}` : '';
  for (const [id, key] of [['processed', 'processed'], ['dated', 'dated'], ['unhandled', 'unhandled'], ['already-present', 'alreadyPresent'], ['errors', 'errors']]) $(id).textContent = number(job[key]);
  $('dated-label').textContent = job.preview ? 'Kan sorteras efter datum' : 'Sorterade efter datum';
  $('output-path').textContent = `Målmapp: ${job.outputDir}`;
  $('report-path').hidden = !job.report;
  $('report-path').textContent = job.report ? `Fullständig CSV-rapport: ${job.report}` : '';
  $('date-sources').textContent = Object.entries(job.dateSources).map(([key, count]) => `${dateSourceLabel(key)}: ${number(count)}`).join(' · ') + (job.skipped ? ` · Överhoppade länkar / specialfiler: ${number(job.skipped)}` : '');
  const note = $('completion-note');
  note.hidden = running;
  note.textContent = job.preview && (job.status === 'CANCELLED' || job.status === 'FAILED')
    ? 'Inga filer ändrades. Förhandsgranskningen är ofullständig och visar bara de filer som hann bearbetas.'
    : job.preview && job.processed === 0 ? 'Inga filer ändrades. Inga filer hittades med de valda inställningarna. Prova att ta med undermappar eller välja en annan mapp.'
    : job.preview ? 'Inga filer ändrades. Granska målmapparna nedan och använd sedan sorteringsknappen ovan. Vid sortering läses mappen igen, så ändringar efter förhandsgranskningen kommer med.'
    : job.status === 'CANCELLED' || job.status === 'FAILED' ? 'Färdiga filer finns kvar i målmappen. Filer som ännu inte bearbetats ligger kvar i källmappen. Granska rapporten innan du börjar igen.'
    : job.errors ? 'Vissa filer kunde inte bearbetas. Granska felen och CSV-rapporten. Filer som inte kunde läsas eller skrivas ligger kvar i källmappen.'
    : job.processed === 0 ? 'Inga filer hittades med de valda inställningarna. Prova att ta med undermappar eller välja en annan mapp.'
    : 'Klart. Identiska filer som redan finns i målmappen hoppades över och deras original behölls.';
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
    for (const value of [basename(sample.source), sample.destination || '—', dateSourceLabel(sample.dateSource), resultLabel(sample.result)]) {
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
  $('folder-title').textContent = target === 'source' ? 'Välj mapp med bilder och videor' : 'Välj målmapp';
  $('folder-filter').value = '';
  $('folder-dialog').showModal();
  await loadFolders($(target).value.trim() || config.home);
}

async function loadFolders(path, filter = '') {
  const request = ++folderRequest;
  $('use-folder').disabled = true;
  $('folder-error').hidden = true;
  $('folder-note').textContent = 'Läser mappar…';
  $('folder-list').replaceChildren();
  try {
    const listing = await api(`/api/folders?path=${encodeURIComponent(path)}&filter=${encodeURIComponent(filter)}`);
    if (request !== folderRequest || !$('folder-dialog').open) return;
    folderLocation = listing.path;
    folderParent = listing.parent;
    $('folder-path').value = listing.path;
    $('folder-up').disabled = !listing.parent;
    $('use-folder').disabled = false;
    $('folder-note').textContent = listing.truncated ? 'Visar de första 1 000 matchande mapparna. Använd filtret för att begränsa listan.' : listing.folders.length ? 'Öppna en undermapp eller välj den aktuella mappen nedan.' : 'Inga matchande undermappar. Du kan välja den här mappen.';
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
    $('folder-error').textContent = `Mappen kunde inte öppnas. ${error.message}`;
    $('folder-error').hidden = false;
    $('folder-note').textContent = 'Ange sökvägen till en befintlig mapp eller välj Hem / en enhet ovan.';
  }
}

function navigateFolder(path) {
  clearTimeout(filterTimer);
  $('folder-filter').value = '';
  $('folder-path').value = path;
  loadFolders(path);
}

$('sort-form').addEventListener('submit', event => { event.preventDefault(); start(true); });
$('source').addEventListener('invalid', () => {
  if ($('source').validity.valueMissing) $('source').setCustomValidity('Välj en mapp eller ange dess fullständiga sökväg.');
});
$('source').addEventListener('input', () => $('source').setCustomValidity(''));
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
  $(folderTarget).setCustomValidity('');
  $('folder-dialog').close();
});

async function initialize() {
  try {
    config = await api('/api/config');
    const readers = ['Inbyggd metadataläsare'];
    if (config.exifToolAvailable) readers.push('ExifTool hittades');
    if (config.ffprobeAvailable) readers.push('ffprobe hittades');
    const missing = [!config.exifToolAvailable && 'ExifTool', !config.ffprobeAvailable && 'ffprobe'].filter(Boolean);
    $('metadata-status').textContent = readers.join(' + ') + '.'
      + (missing.length ? ` Valfria verktyg (${missing.join(' / ')}) ger stöd för fler metadataformat. Se README för installation.` : '');
    for (const shortcut of [{ label: 'Hem', path: config.home }, ...config.roots.map(path => ({ label: path, path }))]) {
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
    showError(`Det gick inte att ansluta till File Sorter. Starta Java-programmet och ladda sedan om sidan. ${error.message}`);
  }
}

initialize();
