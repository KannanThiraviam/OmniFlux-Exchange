/* OmniFlux Exchange UI - data-first landing.
   Land on the table, filter, export exactly the filtered set. */
(function () {
  'use strict';

  const $ = id => document.getElementById(id);

  const dom = {
    navButtons: document.querySelectorAll('.nav-button'),
    toast: $('toast'),
    authBanner: $('auth-banner'),
    selRelation: $('sel-relation'),
    filterColumn: $('filter-column'),
    filterOperator: $('filter-operator'),
    filterValue: $('filter-value'),
    btnAddFilter: $('btn-add-filter'),
    btnClearFilters: $('btn-clear-filters'),
    filterList: $('filter-list'),
    browseTable: $('browse-table'),
    btnPrevPage: $('btn-prev-page'),
    btnNextPage: $('btn-next-page'),
    pageHint: $('page-hint'),
    selFormat: $('sel-format'),
    txtColumns: $('txt-columns'),
    columnOptions: $('column-options'),
    btnExport: $('btn-export'),
    estimateLine: $('estimate-line'),
    exportMessage: $('export-message'),
    btnPrevJobs: $('btn-prev-jobs'),
    btnNextJobs: $('btn-next-jobs'),
    btnRefreshJobs: $('btn-refresh-jobs'),
    jobTable: $('job-table'),
    attemptPanel: $('attempt-panel'),
    attemptTitle: $('attempt-title'),
    btnCloseAttempts: $('btn-close-attempts'),
    attemptTable: $('attempt-table'),
    btnRefreshSystem: $('btn-refresh-system'),
    systemAsof: $('system-asof'),
    systemGrid: $('system-grid'),
    dialog: $('confirm-dialog'),
    confirmText: $('confirm-text'),
    btnConfirmYes: $('btn-confirm-yes'),
    btnConfirmNo: $('btn-confirm-no')
  };

  const state = {
    view: 'data',
    relation: '',
    relationMeta: new Map(),
    filterColumns: [],
    filters: [],
    rowCount: null,
    relationRowCount: null,
    browseController: null,
    browseSeq: 0,
    browseCursor: null,
    browseNextCursor: null,
    browseHistory: [],
    estimateSeq: 0,
    estimateController: null,
    estimatePending: false,
    jobCursor: null,
    jobNextCursor: null,
    jobHistory: [],
    jobRows: new Map(),
    lastJobsHadActive: false,
    runningJob: null,
    pollTimer: null,
    pollDelay: 1500,
    failedPolls: 0
  };

  const numberFormat = new Intl.NumberFormat('en-US');
  const fmt = value => value == null ? '' : numberFormat.format(value);

  const text = (node, value) => { node.textContent = value == null ? '' : String(value); };

  const api = (url, options) => fetch(url, options || {}).then(async response => {
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw Object.assign(new Error(body.message || body.code || response.statusText), {
        status: response.status,
        body
      });
    }
    return body;
  });

  function showToast(message, isError) {
    text(dom.toast, message);
    dom.toast.classList.toggle('error', Boolean(isError));
    if (showToast.timer) clearTimeout(showToast.timer);
    showToast.timer = setTimeout(() => { text(dom.toast, ''); dom.toast.classList.remove('error'); },
      isError ? 8000 : 5000);
  }

  function setMessage(node, message, isError) {
    text(node, message || '');
    node.classList.toggle('error', Boolean(isError));
    node.setAttribute('role', isError ? 'alert' : 'status');
  }

  function formatBytes(value) {
    const bytes = Number(value || 0);
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KiB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MiB';
    return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GiB';
  }

  function formatDurationParts(millis) {
    if (millis < 1000) return millis + ' ms';
    const seconds = Math.floor(millis / 1000);
    if (seconds < 60) return (millis / 1000).toFixed(1) + ' s';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return minutes + 'm ' + String(seconds % 60).padStart(2, '0') + 's';
    const hours = Math.floor(minutes / 60);
    return hours + 'h ' + String(minutes % 60).padStart(2, '0') + 'm';
  }

  function jobDuration(job) {
    if (!job.createdAt) return '—';
    const end = job.finishedAt ? new Date(job.finishedAt).getTime() : Date.now();
    return formatDurationParts(Math.max(0, end - new Date(job.createdAt).getTime()));
  }

  const relative = new Intl.RelativeTimeFormat('en-US', { numeric: 'auto' });
  function formatCreated(iso) {
    if (!iso) return '—';
    const then = new Date(iso).getTime();
    const deltaSeconds = Math.round((then - Date.now()) / 1000);
    if (Math.abs(deltaSeconds) < 60) return relative.format(deltaSeconds, 'second');
    if (Math.abs(deltaSeconds) < 3600) return relative.format(Math.round(deltaSeconds / 60), 'minute');
    if (Math.abs(deltaSeconds) < 86400) return relative.format(Math.round(deltaSeconds / 3600), 'hour');
    return relative.format(Math.round(deltaSeconds / 86400), 'day');
  }

  function describeError(job) {
    if (!job.errorCode && !job.errorMessage) return '';
    if (job.errorCode === 'XLSX_ROW_LIMIT') {
      return 'XLSX supports up to 1,048,576 data rows. Use CSV for larger exports.';
    }
    if (job.errorMessage) return job.errorMessage;
    return String(job.errorCode).toLowerCase().replace(/_/g, ' ');
  }

  function encodeFilters(filters) {
    const json = JSON.stringify(filters);
    const b64 = btoa(unescape(encodeURIComponent(json)));
    return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function decodeFilters(encoded) {
    let b64 = encoded.replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4) b64 += '=';
    return JSON.parse(decodeURIComponent(escape(atob(b64))));
  }

  function idempotencyKey() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    const bytes = new Uint8Array(8);
    window.crypto.getRandomValues(bytes);
    return 'ui-' + Date.now() + '-' + Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  }

  // Presigned URLs legitimately point at a different origin than the app
  // (object storage), so the host cannot be allowlisted here - but the
  // protocol can: refuse anything that is not HTTP(S) before navigating.
  function downloadTarget(rawUrl) {
    const parsed = new URL(rawUrl, window.location.origin);
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
      throw new Error('Refusing to navigate to a non-HTTP(S) download URL.');
    }
    return parsed.toString();
  }

  /* View routing (hash-synced so refresh and back survive) */
  function show(view) {
    state.view = view;
    document.querySelectorAll('.view').forEach(node => {
      node.hidden = node.id !== 'view-' + view;
    });
    dom.navButtons.forEach(node => {
      node.classList.toggle('active', node.dataset.view === view);
    });
    if (view === 'jobs') refreshJobs().catch(() => { /* surfaced in the jobs table */ });
    if (view === 'system') refreshSystem().catch(() => { /* surfaced in the grid */ });
    syncHash();
  }

  function syncHash() {
    /* Never write partial data-view state: before loadRelations resolves,
       state.relation is empty, and overwriting a deep link here is what broke
       URL restoration. Hold the old hash until the relation is known. */
    if (state.view === 'data' && !state.relation) return;
    const parts = [state.view];
    if (state.view === 'data') {
      parts.push(state.relation || '');
      if (state.filters.length) parts.push(encodeFilters(state.filters));
    }
    history.replaceState(null, '', '#' + parts.join('/'));
  }

  function parseHash() {
    const segments = location.hash.replace(/^#\/?/, '').split('/');
    const view = ['data', 'jobs', 'system'].includes(segments[0]) ? segments[0] : 'data';
    return { view, relation: segments[1] || '', encodedFilters: segments[2] || '' };
  }

  /* Relations + counts: ONE response carries names and best-effort counts,
     so the picker renders from a single consistent snapshot - no race.
     `initial` is the hash snapshot taken at page load, BEFORE any code could
     rewrite the URL - re-parsing here would read wiped state. */
  async function loadRelations(initial) {
    initial = initial || parseHash();
    const data = await api('/api/meta/relations');
    const items = data.relations || data.items || [];
    state.relationMeta = new Map(items.map(item => [item.name, item]));
    dom.selRelation.replaceChildren();
    items.forEach(item => {
      const option = document.createElement('option');
      option.value = item.name;
      text(option, item.name);
      dom.selRelation.append(option);
    });
    labelRelationOptions();
    let remembered = null;
    try { remembered = localStorage.getItem('omniflux.table'); } catch (error) { /* private mode */ }
    const preferred = items.find(item => item.name === initial.relation)
      || items.find(item => item.name === remembered && usableDefault(item))
      || items.find(item => Number.isFinite(item.rowCount) && item.rowCount > 0)
      || items.find(item => !Number.isFinite(item.rowCount)) /* unknown outranks known-empty */
      || items[0];
    if (preferred) await selectRelation(preferred.name);
    if (initial.encodedFilters) {
      try {
        const restored = decodeFilters(initial.encodedFilters);
        if (Array.isArray(restored)) {
          state.filters = restored;
          renderFilterList();
        }
      } catch (error) {
        console.debug('restoring filters from URL failed', error);
      }
    }
  }

  /** A remembered table is only worth restoring if it still has rows (or its
      count is unknown - unknown is not empty). */
  function usableDefault(item) {
    return !Number.isFinite(item.rowCount) || item.rowCount > 0;
  }

  function labelRelationOptions() {
    Array.from(dom.selRelation.options).forEach(option => {
      const meta = state.relationMeta.get(option.value);
      text(option, meta && Number.isFinite(meta.rowCount)
        ? meta.name + ' · ' + fmt(meta.rowCount) + ' rows'
        : meta ? meta.name : option.value);
    });
  }

  /* Table selection + filter columns */
  async function selectRelation(name) {
    if (!name || state.relation === name) return;
    state.relation = name;
    dom.selRelation.value = name;
    try { localStorage.setItem('omniflux.table', name); } catch (error) { /* private mode */ }
    state.browseCursor = null;
    state.browseNextCursor = null;
    state.browseHistory = [];
    state.filters = [];
    await loadFilterColumns(name);
    syncHash();
  }

  async function loadFilterColumns(relation) {
    const data = await api('/api/meta/relations/' + encodeURIComponent(relation) + '/columns');
    state.filterColumns = data.columns || [];
    dom.filterColumn.replaceChildren();
    state.filterColumns.forEach(column => {
      const option = document.createElement('option');
      option.value = column.name;
      text(option, column.name + ' (' + column.logicalType.toLowerCase() + ')');
      dom.filterColumn.append(option);
    });
    dom.columnOptions.replaceChildren();
    state.filterColumns.forEach(column => {
      const option = document.createElement('option');
      option.value = column.name;
      dom.columnOptions.append(option);
    });
    renderFilterList();
  }

  /* Filters */
  function renderFilterList() {
    dom.filterList.replaceChildren();
    state.filters.forEach((filter, index) => {
      const chip = document.createElement('span');
      chip.className = 'filter-chip';
      const value = Array.isArray(filter.value) ? filter.value.join(', ') : filter.value;
      const operatorLabel = operatorWord(filter.operator);
      const label = filter.column + ' ' + operatorLabel + ' ' + value;
      text(chip, label);
      const remove = document.createElement('button');
      remove.type = 'button';
      text(remove, '×');
      remove.setAttribute('aria-label', 'Remove filter: ' + label);
      remove.addEventListener('click', () => {
        state.filters.splice(index, 1);
        afterFiltersChanged();
      });
      chip.append(remove);
      dom.filterList.append(chip);
    });
    dom.btnClearFilters.hidden = state.filters.length === 0;
  }

  function operatorWord(operator) {
    const words = {
      EQ: 'equals', NE: 'does not equal', GT: 'greater than', GTE: 'at least',
      LT: 'less than', LTE: 'at most', IN: 'is one of'
    };
    return words[operator] || String(operator || '').toLowerCase();
  }

  function filterColumn(name) {
    return state.filterColumns.find(column => column.name === name);
  }

  function typedFilterValue(raw, column) {
    const value = raw.trim();
    if (!value) throw new Error('Enter a value to filter on.');
    if (column.logicalType === 'INTEGER') {
      const number = Number(value);
      if (!Number.isSafeInteger(number)) throw new Error('Enter a whole number for ' + column.name + '.');
      return number;
    }
    if (column.logicalType === 'DECIMAL') {
      const number = Number(value);
      if (!Number.isFinite(number)) throw new Error('Enter a number for ' + column.name + '.');
      return number;
    }
    if (column.logicalType === 'BOOLEAN') {
      if (!['true', 'false'].includes(value.toLowerCase())) {
        throw new Error('Enter true or false for ' + column.name + '.');
      }
      return value.toLowerCase() === 'true';
    }
    return value;
  }

  function addFilter() {
    const column = filterColumn(dom.filterColumn.value);
    if (!column) {
      setMessage(dom.exportMessage, 'Choose a column to filter on.', true);
      return;
    }
    const operator = dom.filterOperator.value;
    const raw = dom.filterValue.value;
    let value;
    try {
      value = operator === 'IN'
        ? raw.split(',').map(part => part.trim()).filter(Boolean)
            .map(part => typedFilterValue(part, column))
        : typedFilterValue(raw, column);
    } catch (error) {
      setMessage(dom.exportMessage, error.message, true);
      return;
    }
    if (operator === 'IN' && (!Array.isArray(value) || !value.length)) {
      setMessage(dom.exportMessage, 'Enter at least one value for "is one of".', true);
      return;
    }
    state.filters.push({ column: column.name, operator, value });
    dom.filterValue.value = '';
    afterFiltersChanged();
  }

  function clearFilters() {
    state.filters = [];
    afterFiltersChanged();
  }

  function afterFiltersChanged() {
    renderFilterList();
    state.browseHistory = [];
    syncHash();
    refreshEstimate();
    loadBrowse(null).catch(error => {
      if (error.name !== 'AbortError') setMessage(dom.exportMessage, error.message, true);
    });
  }

  /* Browse (data table) */
  function skeletonRows() {
    const wrap = document.createElement('div');
    wrap.className = 'skeleton';
    wrap.setAttribute('role', 'status');
    const label = document.createElement('span');
    label.className = 'visually-hidden';
    text(label, 'Loading rows…');
    wrap.append(label);
    [100, 86, 92, 78, 95].forEach(width => {
      const bar = document.createElement('div');
      bar.className = 'skel-row';
      bar.style.width = width + '%';
      wrap.append(bar);
    });
    return wrap;
  }

  function renderBrowseError(error) {
    dom.browseTable.replaceChildren();
    const p = document.createElement('div');
    p.className = 'empty';
    text(p, 'Could not load rows. ' + error.message);
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'link-button';
    text(retry, 'Retry');
    retry.addEventListener('click', () => loadBrowse(state.browseCursor).catch(() => {}));
    p.append(retry);
    dom.browseTable.append(p);
  }

  async function loadBrowse(cursor) {
    if (!state.relation) return;
    if (state.browseController) state.browseController.abort();
    const controller = new AbortController();
    state.browseController = controller;
    const seq = ++state.browseSeq;
    state.browseCursor = cursor || null;
    dom.btnPrevPage.disabled = true;
    dom.btnNextPage.disabled = true;
    dom.browseTable.replaceChildren(skeletonRows());
    const query = new URLSearchParams({ limit: '50' });
    if (cursor) query.set('after', cursor);
    if (state.filters.length) query.set('filters', JSON.stringify(state.filters));
    let data;
    try {
      data = await api('/api/data/' + encodeURIComponent(state.relation) + '?' + query,
        { signal: controller.signal });
    } catch (error) {
      if (error.name === 'AbortError' || seq !== state.browseSeq) return;
      renderBrowseError(error);
      return;
    }
    if (seq !== state.browseSeq) return;
    state.browseController = null;
    state.browseNextCursor = data.nextCursor || null;
    dom.btnPrevPage.disabled = state.browseHistory.length === 0;
    dom.btnNextPage.disabled = !state.browseNextCursor;
    const rows = data.rows || data.items || [];
    renderDataTable(rows);
  }

  function renderDataTable(rows) {
    dom.browseTable.replaceChildren();
    if (!rows.length) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      if (state.filters.length) {
        text(empty, 'No rows match your filters.');
        const clear = document.createElement('button');
        clear.type = 'button';
        clear.className = 'link-button';
        text(clear, 'Remove all filters');
        clear.addEventListener('click', clearFilters);
        empty.append(clear);
      } else if (state.browseHistory.length) {
        text(empty, 'End of results.');
      } else {
        text(empty, 'This table has no rows. Pick a different table above.');
      }
      dom.browseTable.append(empty);
      return;
    }
    const table = document.createElement('table');
    const caption = document.createElement('caption');
    caption.className = 'visually-hidden';
    text(caption, state.relation + ' rows, page ' + (state.browseHistory.length + 1));
    table.append(caption);
    const head = table.createTHead().insertRow();
    const keys = Object.keys(rows[0]);
    keys.forEach(key => {
      const th = document.createElement('th');
      if (isNumericColumn(key, rows)) th.classList.add('num');
      text(th, key);
      head.append(th);
    });
    const body = table.createTBody();
    rows.forEach(row => {
      const tr = body.insertRow();
      keys.forEach(key => {
        const cell = tr.insertCell();
        renderCell(cell, key, row[key]);
      });
    });
    dom.browseTable.append(table);
  }

  function isNumericColumn(key, rows) {
    return rows.some(row => typeof row[key] === 'number');
  }

  function renderCell(cell, key, value) {
    if (value == null) return; /* leave empty for null */
    if (typeof value === 'number') {
      cell.classList.add('num');
      const isKey = key === 'id' || key.endsWith('_id');
      text(cell, isKey ? String(value) : fmt(value));
      cell.title = String(value);
      return;
    }
    const display = String(value);
    text(cell, display);
    if (display.length > 24) cell.title = display;
  }

  function nextBrowsePage() {
    if (!state.browseNextCursor) return;
    state.browseHistory.push(state.browseCursor);
    loadBrowse(state.browseNextCursor).catch(error => {
      if (error.name !== 'AbortError') renderBrowseError(error);
    });
  }

  function previousBrowsePage() {
    if (!state.browseHistory.length) return;
    const cursor = state.browseHistory.pop();
    loadBrowse(cursor).catch(error => {
      if (error.name !== 'AbortError') renderBrowseError(error);
    });
  }

  /* Estimate + export */
  const XLSX_MAX_DATA_ROWS = 1048576;
  const EXPORT_CONFIRM_ROWS = 100000;

  async function refreshEstimate() {
    if (!state.relation) return;
    if (state.estimateController) state.estimateController.abort();
    const controller = new AbortController();
    state.estimateController = controller;
    const seq = ++state.estimateSeq;
    state.estimatePending = true;
    updateExportUi(); /* settle the CTA LAST: show counting, disable stale label */
    let count = null;
    try {
      const data = await api('/api/data/' + encodeURIComponent(state.relation) + '/count' + filterQueryString(), { signal: controller.signal });
      if (seq !== state.estimateSeq) return;
      count = Number(data.count);
    } catch (error) {
      if (error.name === 'AbortError') return;
      count = null; /* estimate unavailable: never blocks exporting */
    }
    state.estimatePending = false;
    state.rowCount = Number.isFinite(count) ? count : null;
    updateExportUi();
  }

  function filterQueryString() {
    return state.filters.length ? '?filters=' + encodeURIComponent(JSON.stringify(state.filters)) : '';
  }

  function exportLabel() {
    const noun = state.filters.length ? 'matching rows' : 'rows';
    if (state.rowCount == null) return state.filters.length ? 'Export filtered data' : 'Export all rows';
    return 'Export ' + fmt(state.rowCount) + ' ' + noun;
  }

  function updateExportUi() {
    dom.estimateLine.classList.remove('warn');
    if (state.estimatePending) {
      /* The button is the contract; while the count that names it is in
         flight, hold the previous label grayed instead of showing stale text
         that contradicts the visible filters. */
      dom.btnExport.disabled = true;
      dom.estimateLine.classList.add('pending');
      text(dom.estimateLine, 'Counting matching rows…');
      return;
    }
    dom.estimateLine.classList.remove('pending');
    if (state.rowCount === 0) {
      dom.btnExport.disabled = true;
      dom.btnExport.textContent = 'Nothing to export';
      text(dom.estimateLine, state.filters.length
        ? '0 rows match your filters. Remove or change a filter.'
        : 'This table has no rows to export.');
      return;
    }
    dom.btnExport.textContent = exportLabel();
    const lines = [];
    if (state.rowCount != null) {
      lines.push(state.filters.length
        ? fmt(state.rowCount) + ' rows match your filters.'
        : fmt(state.rowCount) + ' rows in this table. Add filters to export a subset.');
    }
    const xlsxOverLimit = dom.selFormat.value === 'XLSX' && state.rowCount != null
      && state.rowCount > XLSX_MAX_DATA_ROWS;
    dom.btnExport.disabled = xlsxOverLimit;
    if (xlsxOverLimit) {
      dom.estimateLine.classList.add('warn');
      lines.push('XLSX supports up to ' + fmt(XLSX_MAX_DATA_ROWS) + ' rows. Use CSV for larger exports.');
    }
    text(dom.estimateLine, lines.join(' '));
  }

  function exportPayload() {
    const columns = dom.txtColumns.value.split(',').map(value => value.trim()).filter(Boolean);
    return {
      relation: state.relation,
      columns,
      format: dom.selFormat.value,
      csvMode: 'SPREADSHEET_SAFE',
      filters: state.filters
    };
  }

  function confirmLargeExport(count) {
    return new Promise(resolve => {
      text(dom.confirmText, 'Export ' + fmt(count) + ' rows as ' + dom.selFormat.value
        + '? The whole filtered set streams to object storage.');
      const onYes = () => finish(true);
      const onNo = () => finish(false);
      function finish(result) {
        dom.btnConfirmYes.removeEventListener('click', onYes);
        dom.btnConfirmNo.removeEventListener('click', onNo);
        dom.dialog.close();
        resolve(result);
      }
      dom.btnConfirmYes.addEventListener('click', onYes, { once: true });
      dom.btnConfirmNo.addEventListener('click', onNo, { once: true });
      dom.dialog.addEventListener('close', () => finish(false), { once: true });
      dom.dialog.showModal();
    });
  }

  async function queueExport() {
    if (!state.relation) return;
    if (state.rowCount === 0) return; /* belt for the disabled-button braces */
    if (state.rowCount != null && state.rowCount > EXPORT_CONFIRM_ROWS) {
      const proceed = await confirmLargeExport(state.rowCount);
      if (!proceed) return;
    }
    try {
      const response = await api('/api/exports', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey() },
        body: JSON.stringify(exportPayload())
      });
      state.runningJob = response.id || response.jobId;
      setMessage(dom.exportMessage, response.cacheHit
        ? 'Cache hit. The existing file is ready in Jobs.'
        : 'Export queued. Follow it in Jobs.');
      const link = document.createElement('button');
      link.type = 'button';
      link.className = 'link-button';
      text(link, 'Open Jobs');
      link.addEventListener('click', () => show('jobs'));
      dom.exportMessage.append(' ', link);
      startPolling();
    } catch (error) {
      setMessage(dom.exportMessage, error.message, true);
    }
  }

  /* Jobs */
  function statusTone(status) {
    if (status === 'COMPLETED') return 'ok';
    if (status === 'FAILED') return 'err';
    if (status === 'IN_PROGRESS' || status === 'QUEUED') return 'run';
    return 'idle';
  }

  function statusLabel(status) {
    const labels = {
      QUEUED: 'Queued', IN_PROGRESS: 'Running', COMPLETED: 'Completed',
      FAILED: 'Failed', CANCELLED: 'Cancelled'
    };
    return labels[status] || status || '';
  }

  function jobsTbody() {
    let table = dom.jobTable.querySelector('table');
    if (!table) {
      table = document.createElement('table');
      const caption = document.createElement('caption');
      caption.className = 'visually-hidden';
      text(caption, 'Export jobs');
      const head = table.createTHead().insertRow();
      ['Table', 'Format', 'Status', 'Created', 'Rows', 'Size', 'Duration', 'Error', 'Actions']
        .forEach(label => {
          const th = document.createElement('th');
          text(th, label);
          head.append(th);
        });
      table.append(document.createElement('tbody'));
      dom.jobTable.replaceChildren(table);
    }
    return table.tBodies[0];
  }

  function buildJobRow() {
    const tr = document.createElement('tr');
    const cells = {};
    ['relation', 'format', 'status', 'created', 'rows', 'size', 'duration', 'error', 'actions']
      .forEach(key => {
        const td = tr.insertCell();
        if (key === 'rows' || key === 'size' || key === 'duration') td.classList.add('num');
        if (key === 'actions') td.className = 'actions';
        cells[key] = td;
      });
    return { tr, cells, status: null };
  }

  function patchJobRow(entry, job) {
    text(entry.cells.relation, job.relation);
    text(entry.cells.format, job.format);
    if (entry.status !== job.status) {
      entry.status = job.status;
      const badge = document.createElement('span');
      badge.className = 'badge ' + statusTone(job.status);
      text(badge, statusLabel(job.status));
      entry.cells.status.replaceChildren(badge);
      patchJobActions(entry, job);
    }
    const created = document.createElement('time');
    if (job.createdAt) {
      created.dateTime = job.createdAt;
      created.title = new Date(job.createdAt).toLocaleString();
    }
    text(created, formatCreated(job.createdAt));
    entry.cells.created.replaceChildren(created);
    const rowsText = job.status === 'IN_PROGRESS'
      ? (job.rowCount ? fmt(job.rowCount) + ' so far' : 'starting…')
      : (job.rowCount ? fmt(job.rowCount) : '—');
    text(entry.cells.rows, rowsText);
    text(entry.cells.size, job.byteCount ? formatBytes(job.byteCount) : '—');
    text(entry.cells.duration, jobDuration(job));
    const errorText = describeError(job);
    text(entry.cells.error, errorText);
    entry.cells.error.title = job.errorCode || '';
  }

  function patchJobActions(entry, job) {
    const wrap = document.createElement('div');
    wrap.className = 'inline-actions';
    const what = job.relation + ' ' + (job.format || '').toLowerCase();
    if (job.status === 'COMPLETED') {
      wrap.append(actionButton('Download', () => downloadJob(job.id), 'primary',
        'Download ' + what));
    }
    if (job.status === 'FAILED' || job.status === 'CANCELLED') {
      wrap.append(actionButton('Retry', () => retryJob(job.id), '', 'Retry ' + what));
    }
    if (job.status === 'QUEUED' || job.status === 'IN_PROGRESS') {
      wrap.append(actionButton('Cancel', () => cancelJob(job.id), '', 'Cancel ' + what));
    }
    wrap.append(actionButton('Attempts', () => viewAttempts(job), '', 'Attempts for ' + what));
    entry.cells.actions.replaceChildren(wrap);
  }

  function actionButton(label, handler, className, ariaLabel) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = className || '';
    text(button, label);
    if (ariaLabel) button.setAttribute('aria-label', ariaLabel);
    button.addEventListener('click', handler);
    return button;
  }

  function renderJobs(items) {
    const ids = new Set(items.map(job => job.id));
    state.jobRows.forEach((entry, id) => {
      if (!ids.has(id)) {
        entry.tr.remove();
        state.jobRows.delete(id);
      }
    });
    const tbody = jobsTbody();
    items.forEach((job, index) => {
      let entry = state.jobRows.get(job.id);
      if (!entry) {
        entry = buildJobRow();
        state.jobRows.set(job.id, entry);
      }
      patchJobRow(entry, job);
      const expected = tbody.children[index];
      if (expected !== entry.tr) tbody.insertBefore(entry.tr, expected || null);
    });
  }

  function renderJobsEmpty() {
    dom.jobTable.replaceChildren();
    const empty = document.createElement('div');
    empty.className = 'empty';
    text(empty, 'No exports yet. Filter your data and click Export.');
    const go = document.createElement('button');
    go.type = 'button';
    go.className = 'link-button';
    text(go, 'Go to Data');
    go.addEventListener('click', () => show('data'));
    empty.append(go);
    dom.jobTable.append(empty);
  }

  async function loadJobs(cursor) {
    const query = new URLSearchParams({ limit: '50' });
    if (cursor) query.set('after', cursor);
    const data = await api('/api/jobs?' + query);
    state.jobCursor = cursor || null;
    state.jobNextCursor = data.nextCursor || null;
    dom.btnPrevJobs.disabled = state.jobHistory.length === 0;
    dom.btnNextJobs.disabled = !state.jobNextCursor;
    const items = data.items || data.jobs || [];
    state.lastJobsHadActive = items.some(job =>
      job.status === 'QUEUED' || job.status === 'IN_PROGRESS');
    if (state.lastJobsHadActive || state.runningJob) startPolling();
    if (!items.length && !state.jobHistory.length) {
      renderJobsEmpty();
    } else {
      renderJobs(items);
    }
    if (state.runningJob && !items.some(job => job.id === state.runningJob
        && ['QUEUED', 'IN_PROGRESS'].includes(job.status))) {
      state.runningJob = null;
    }
    return data;
  }

  async function refreshJobs() {
    state.jobHistory = [];
    try {
      await loadJobs(null);
    } catch (error) {
      dom.jobTable.replaceChildren();
      const empty = document.createElement('div');
      empty.className = 'empty';
      text(empty, 'Could not load jobs. ' + error.message);
      const retry = document.createElement('button');
      retry.type = 'button';
      retry.className = 'link-button';
      text(retry, 'Retry');
      retry.addEventListener('click', () => refreshJobs().catch(() => {}));
      empty.append(retry);
      dom.jobTable.append(empty);
      throw error;
    }
  }

  async function nextJobsPage() {
    if (!state.jobNextCursor) return;
    state.jobHistory.push(state.jobCursor);
    try {
      await loadJobs(state.jobNextCursor);
    } catch (error) {
      showToast('Could not load the next jobs page: ' + error.message, true);
    }
  }

  async function previousJobsPage() {
    if (!state.jobHistory.length) return;
    const cursor = state.jobHistory.pop();
    try {
      await loadJobs(cursor);
    } catch (error) {
      showToast('Could not load the previous jobs page: ' + error.message, true);
    }
  }

  /* Attempts */
  async function viewAttempts(job) {
    try {
      const attempts = await api('/api/jobs/' + encodeURIComponent(job.id) + '/attempts');
      const rows = Array.isArray(attempts) ? attempts : (attempts.items || []);
      dom.attemptPanel.hidden = false;
      text(dom.attemptTitle, 'Attempt history — ' + job.relation);
      dom.attemptTable.replaceChildren();
      const table = document.createElement('table');
      const caption = document.createElement('caption');
      caption.className = 'visually-hidden';
      text(caption, 'Attempts for job on ' + job.relation);
      table.append(caption);
      const head = table.createTHead().insertRow();
      ['Attempt', 'Worker', 'Status', 'Rows', 'Size', 'Error'].forEach(label => {
        const th = document.createElement('th');
        text(th, label);
        head.append(th);
      });
      const body = table.createTBody();
      rows.forEach(attempt => {
        const tr = body.insertRow();
        [attempt.attemptNo, attempt.workerId || '', statusLabel(attempt.status) || attempt.status,
          attempt.rowCount ? fmt(attempt.rowCount) : '—',
          attempt.byteCount ? formatBytes(attempt.byteCount) : '—',
          describeError(attempt)].forEach(value => {
          const cell = tr.insertCell();
          text(cell, value == null ? '' : value);
        });
      });
      dom.attemptTable.append(table);
      dom.attemptPanel.scrollIntoView({ behavior: motionAllowed() ? 'smooth' : 'auto', block: 'nearest' });
    } catch (error) {
      showToast('Could not load attempts: ' + error.message, true);
    }
  }

  function motionAllowed() {
    return !window.matchMedia || !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  /* System */
  async function refreshSystem() {
    try {
      const data = await api('/api/system/resources');
      const authMode = data.authMode === 'DISABLED' ? 'Disabled (demo)' : data.authMode;
      const values = [
        ['Auth mode', authMode],
        ['Heap', formatBytes(data.heap?.usedBytes)],
        ['RSS', formatBytes(data.rss?.usedBytes)],
        ['Active jobs', fmt(data.jobs?.active)],
        ['Queued jobs', fmt(data.jobs?.queued)],
        ['Global active', fmt(data.jobs?.globalActive)],
        ['Rows/sec (5m)', Number(data.rowsPerSec || 0).toFixed(1)],
        ['Cache hit rate', (Number(data.cacheHitRate || 0) * 100).toFixed(1) + '%']
      ];
      dom.systemGrid.replaceChildren();
      values.forEach(([label, value]) => dom.systemGrid.append(metricCard(label, value)));
      dom.systemAsof.textContent = 'Updated ' + new Date().toLocaleTimeString();
    } catch (error) {
      dom.systemGrid.replaceChildren();
      const empty = document.createElement('div');
      empty.className = 'empty';
      text(empty, 'Could not load system metrics. ' + error.message);
      dom.systemGrid.append(empty);
      throw error;
    }
  }

  function metricCard(label, value) {
    const card = document.createElement('div');
    card.className = 'metric';
    const small = document.createElement('span');
    small.className = 'metric-label';
    text(small, label);
    const strong = document.createElement('strong');
    text(strong, value);
    card.append(small, strong);
    return card;
  }

  /* Polling with backoff */
  function shouldKeepPolling() {
    return state.runningJob != null || (state.view === 'jobs' && state.lastJobsHadActive);
  }

  function startPolling() {
    if (state.pollTimer) return;
    state.pollDelay = 1500;
    state.failedPolls = 0;
    schedulePoll(0);
  }

  function schedulePoll(delay) {
    if (state.pollTimer) return;
    state.pollTimer = setTimeout(async () => {
      state.pollTimer = null;
      try {
        await loadJobs(state.jobCursor);
        state.failedPolls = 0;
        state.pollDelay = 1500;
      } catch (error) {
        state.failedPolls += 1;
        state.pollDelay = Math.min(10000, state.pollDelay * 2);
        showToast('Job refresh failed; retrying in ' + Math.round(state.pollDelay / 1000) + 's.', true);
      }
      if (shouldKeepPolling()) schedulePoll(state.pollDelay);
    }, delay);
  }

  function stopPollingIfIdle() {
    if (!shouldKeepPolling() && !state.pollTimer) return;
    if (!shouldKeepPolling() && state.pollTimer) {
      clearTimeout(state.pollTimer);
      state.pollTimer = null;
    }
  }

  /* Jobs actions */
  async function cancelJob(id) {
    try {
      await api('/api/jobs/' + encodeURIComponent(id) + '/cancel', { method: 'POST' });
      showToast('Cancellation requested.');
      await refreshJobs();
    } catch (error) {
      showToast('Could not cancel: ' + error.message, true);
    }
  }

  async function retryJob(id) {
    try {
      const response = await api('/api/jobs/' + encodeURIComponent(id) + '/retry', { method: 'POST' });
      state.runningJob = response.id || id;
      showToast('Retry queued.');
      startPolling();
      await refreshJobs();
    } catch (error) {
      showToast('Could not retry: ' + error.message, true);
    }
  }

  async function downloadJob(id) {
    try {
      const response = await api('/api/jobs/' + encodeURIComponent(id) + '/download');
      window.location.assign(downloadTarget(response.url));
    } catch (error) {
      showToast('Could not start the download: ' + error.message, true);
    }
  }

  /* Wiring */
  dom.navButtons.forEach(button => {
    button.addEventListener('click', () => show(button.dataset.view));
  });
  dom.selRelation.addEventListener('change', event => {
    selectRelation(event.target.value)
      .then(() => { refreshEstimate(); return loadBrowse(null); })
      .catch(error => setMessage(dom.exportMessage, error.message, true));
  });
  dom.filterOperator.addEventListener('change', () => updateExportUi());
  dom.selFormat.addEventListener('change', () => updateExportUi());
  dom.btnAddFilter.addEventListener('click', addFilter);
  dom.filterValue.addEventListener('keydown', event => {
    if (event.key === 'Enter') addFilter();
  });
  dom.btnClearFilters.addEventListener('click', clearFilters);
  dom.btnExport.addEventListener('click', queueExport);
  dom.btnPrevPage.addEventListener('click', previousBrowsePage);
  dom.btnNextPage.addEventListener('click', nextBrowsePage);
  dom.btnRefreshJobs.addEventListener('click', () => refreshJobs().catch(() => {}));
  dom.btnPrevJobs.addEventListener('click', previousJobsPage);
  dom.btnNextJobs.addEventListener('click', nextJobsPage);
  dom.btnCloseAttempts.addEventListener('click', () => { dom.attemptPanel.hidden = true; });
  dom.btnRefreshSystem.addEventListener('click', () => refreshSystem().catch(() => {}));

  /* Init */
  api('/api/system/resources').then(data => {
    if (data.authMode && data.authMode !== 'JWT') {
      dom.authBanner.hidden = false;
      text(dom.authBanner, 'Demo auth: trusted network only');
    }
  }).catch(error => console.debug('auth banner check skipped', error));

  const initial = parseHash();
  show(initial.view);
  loadRelations(initial)
    .then(() => { refreshEstimate(); return loadBrowse(null); })
    .catch(error => setMessage(dom.exportMessage, 'Could not load your tables. ' + error.message, true));

  /* Back/Forward and pasted links: re-apply hash state while the app is open.
     syncHash uses replaceState, which never fires hashchange - no loop. */
  window.addEventListener('hashchange', () => {
    const next = parseHash();
    if (next.view !== state.view) show(next.view);
    if (next.view !== 'data') return;
    const refetch = () => {
      state.browseHistory = [];
      syncHash();
      refreshEstimate();
      loadBrowse(null).catch(error => {
        if (error.name !== 'AbortError') setMessage(dom.exportMessage, error.message, true);
      });
    };
    const restoreFilters = encoded => {
      try {
        const restored = decodeFilters(encoded);
        if (Array.isArray(restored) && JSON.stringify(restored) !== JSON.stringify(state.filters)) {
          state.filters = restored;
          renderFilterList();
          refetch();
        }
      } catch (error) {
        console.debug('shared filters invalid', error);
      }
    };
    if (next.relation && next.relation !== state.relation) {
      selectRelation(next.relation).then(() => {
        if (next.encodedFilters) restoreFilters(next.encodedFilters);
      }).catch(error => setMessage(dom.exportMessage, error.message, true));
    } else if (next.encodedFilters) {
      restoreFilters(next.encodedFilters);
    } else if (state.filters.length) {
      state.filters = [];
      renderFilterList();
      refetch();
    }
  });
}());
