/**
 * Бюджет / Budget — backend for the Android app.
 *
 * Works with the existing layout of "Бюджет / Budget YYYY":
 *  - one tab per month, named "Октомври 2026", "Ноември 2026", ...
 *  - row 1: section headers. A header merged over 2 columns = reason + amount
 *    (e.g. "Сметки дом" A:B); a single-column header = amount only (e.g. "Пазар").
 *  - "Кредит": fixed amount in row 2, carried into every new month.
 *  - "Приходи": header over 4 columns, a person name in row 3 for each
 *    source/amount pair, rows below are sources, then a "Общо …" total row.
 *  - "Баланс" (added by this script) to the right of Приходи:
 *    previous leftover, income, bills, leftover for this month, per-section totals.
 *
 * New month  → a new tab copied from the previous month (labels kept, amounts cleared,
 *              Кредит kept, previous leftover linked).
 * New year   → a new file "Бюджет / Budget YYYY" in the same Drive folder.
 *
 * SETUP (once): see README.md → paste this file into Extensions → Apps Script of the
 * 2026 file, run `setup`, then Deploy → Web app (Execute as: Me, Access: Anyone).
 */

// ---------- configuration ----------
const FILE_PREFIX = 'Бюджет / Budget ';
const MONTHS = ['Януари', 'Февруари', 'Март', 'Април', 'Май', 'Юни',
  'Юли', 'Август', 'Септември', 'Октомври', 'Ноември', 'Декември'];
const INCOME_HEADER = 'Приходи';
const BALANCE_HEADER = 'Баланс';
const FIXED_SECTIONS = ['Кредит'];          // amount carried into each new month
const TOTAL_PREFIX = 'Общо';                // income total rows: "Общо Ади", "Общо Иво"
const INCOME_FIRST_ROW = 4;
const NUM_FORMAT = '#,##0.00';

// Balance block rows (label column = balance column, value column = +1)
const B_PREV = 2, B_INCOME = 3, B_BILLS = 4, B_LEFT = 5, B_BY_SECTION = 7;

// ---------- setup ----------

/** Run once from the editor. Safe to run again. */
function setup() {
  const props = PropertiesService.getScriptProperties();
  if (!props.getProperty('SECRET')) props.setProperty('SECRET', Utilities.getUuid().replace(/-/g, ''));
  const thisFile = DriveApp.getFileById(SpreadsheetApp.getActiveSpreadsheet().getId());
  props.setProperty('FOLDER_ID', thisFile.getParents().next().getId());

  // Add the Баланс block to every existing month tab of this file.
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  rebuildIndex_();
  monthTabs_(ss).forEach(function (t) { ensureBalance_(t.sheet, t.y, t.m); });

  // Create next month's tab automatically on the 1st, even before the first entry.
  ScriptApp.getProjectTriggers().forEach(function (tr) {
    if (tr.getHandlerFunction() === 'ensureCurrentMonth') ScriptApp.deleteTrigger(tr);
  });
  ScriptApp.newTrigger('ensureCurrentMonth').timeBased().onMonthDay(1).atHour(0).create();

  Logger.log('Ready. Your secret key: ' + props.getProperty('SECRET'));
}

/** Time trigger: makes sure this month's tab exists. */
function ensureCurrentMonth() {
  const now = new Date();
  ensureMonth_(now.getFullYear(), now.getMonth() + 1);
}

// ---------- web app ----------

/** Opening the URL in a browser shows a health message; the app may also send ?p=<request>. */
function doGet(e) {
  if (e && e.parameter && e.parameter.p) return handle_(e.parameter.p);
  return ContentService.createTextOutput('Budget backend is running.');
}

function doPost(e) {
  return handle_(e && e.postData ? e.postData.contents : '');
}

function handle_(raw) {
  try {
    const req = JSON.parse(raw);
    const secret = PropertiesService.getScriptProperties().getProperty('SECRET');
    if (!secret || req.secret !== secret) return json_({ ok: false, error: 'Wrong secret key' });

    const lock = LockService.getScriptLock();
    lock.waitLock(25000);
    try {
      try {
        return json_(Object.assign({ ok: true }, run_(req)));
      } catch (err) {
        if (!err || !err.staleIndex) throw err;
        rebuildIndex_(); // a tab was renamed or deleted by hand: re-read the folder once
        return json_(Object.assign({ ok: true }, run_(req)));
      }
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    return json_({ ok: false, error: String(err && err.message || err) });
  }
}

function run_(req) {
  switch (req.action) {
    case 'sync': return sync_(req);
    case 'month': return month_(req);
    case 'load': return load_(req); // used by app versions before 0.4
    case 'add':
    case 'edit': {
      const cache = CacheService.getScriptCache();
      if (req.opId && cache.get('op_' + req.opId)) return { duplicate: true };
      const sheetName = req.action === 'add' ? add_(req) : edit_(req);
      if (req.opId) cache.put('op_' + req.opId, '1', 21600);
      return { sheet: sheetName };
    }
    default: throw new Error('Unknown action');
  }
}

/** Manual edits in the sheet: drop the cached copy of that month and the months after it. */
function onEdit(e) {
  try {
    const p = parseTabName_(e.range.getSheet().getName());
    if (p) invalidateFrom_(p.y, p.m);
  } catch (err) { /* never block typing in the sheet */ }
}

// ---------- actions ----------

/**
 * {today:"yyyy-MM-dd", sheet?: "Ноември 2026"} →
 * {months:[names, oldest first], sheet, month:{...}, previous:{section:total}, trend:[...]}
 */
function load_(req) {
  const s = sync_({ today: req.today });
  const all = allMonths_();
  const name = req.sheet && s.months.indexOf(req.sheet) >= 0 ? req.sheet : s.current;
  const i = s.months.indexOf(name);
  const win = all.slice(Math.max(0, i - 5), i + 1);
  const data = monthsData_(win, {});
  const prevTotals = {};
  if (i > 0 && data[s.months[i - 1]]) {
    data[s.months[i - 1]].sections.forEach(function (x) { prevTotals[x.name] = x.total; });
  }
  return {
    months: s.months,
    sheet: name,
    month: data[name],
    previous: prevTotals,
    trend: win.map(function (t) {
      const b = data[t.name].balance;
      return { sheet: t.name, income: b.income, bills: b.bills, leftover: b.leftover };
    }),
  };
}

const SYNC_BACK = 12;          // months before the current one sent with every sync
const MD_TTL = 21600;          // cached month data lives up to 6 h (cleared on every change)

/**
 * {today, fresh?} → {months:[all names], current, data:{name: month}}
 * Sends the current month plus the 12 before it (and any later ones), so the app can
 * switch months and show the analysis without asking again. The current month is always
 * read from the sheet; the others come from the cache unless fresh=true.
 */
function sync_(req) {
  const today = parseDate_(req.today);
  if (req.fresh) rebuildIndex_();
  ensureMonth_(today.y, today.m);
  const all = allMonths_();
  const current = MONTHS[today.m - 1] + ' ' + today.y;
  let idx = -1;
  all.forEach(function (t, i) { if (t.name === current) idx = i; });
  const list = all.slice(Math.max(0, idx - SYNC_BACK));
  const fresh = {};
  fresh[current] = true;
  if (req.fresh) list.forEach(function (t) { fresh[t.name] = true; });
  return { months: all.map(function (t) { return t.name; }), current: current, data: monthsData_(list, fresh) };
}

/** {sheet} → {sheet, data} for a month older than what sync sends. */
function month_(req) {
  const t = allMonths_().filter(function (x) { return x.name === req.sheet; })[0];
  if (!t) throw new Error('Month not found: ' + req.sheet);
  return { sheet: t.name, data: monthsData_([t], {})[t.name] };
}

function mdKey_(y, m) { return 'md_' + y + '_' + m; }

/** Month data for each tab in list, from the cache where possible (one batched cache read). */
function monthsData_(list, fresh) {
  const cache = CacheService.getScriptCache();
  const hit = cache.getAll(list.map(function (t) { return mdKey_(t.y, t.m); })) || {};
  const out = {}, put = {};
  list.forEach(function (t) {
    const k = mdKey_(t.y, t.m);
    if (!fresh[t.name] && hit[k]) { out[t.name] = JSON.parse(hit[k]); return; }
    out[t.name] = monthData_(t.sheet, t.y, t.m);
    put[k] = JSON.stringify(out[t.name]);
  });
  if (Object.keys(put).length) cache.putAll(put, MD_TTL);
  return out;
}

/** A change in a month also changes the leftover of every month after it. */
function invalidateFrom_(y, m) {
  const keys = [];
  for (let i = 0; i < 36; i++) {
    const k = y * 12 + (m - 1) + i;
    keys.push(mdKey_(Math.floor(k / 12), (k % 12) + 1));
  }
  CacheService.getScriptCache().removeAll(keys);
}

/**
 * Expense: {date, kind:"expense", section, label?, amount}
 * Income:  {date, kind:"income",  person,  label,  amount}
 * Goes into the tab of the entry's month (created if missing).
 * Same reason already in the list → its amount is increased (cell shows =a+b).
 */
function add_(req) {
  const d = parseDate_(req.date);
  const sh = ensureMonth_(d.y, d.m);
  const L = layout_(sh);
  const amount = Number(req.amount);
  if (!(amount > 0)) throw new Error('Amount must be positive');
  const label = String(req.label || '').trim();

  if (req.kind === 'income') {
    const p = findByName_(L.persons, req.person, 'person');
    if (!label) throw new Error('Income needs a source');
    let row = findLabelRow_(sh, p.labelCol, INCOME_FIRST_ROW, L.totalRow - 1, label);
    if (!row) row = firstEmptyPairRow_(sh, p.labelCol, INCOME_FIRST_ROW, L.totalRow - 1);
    if (!row) row = insertIncomeRow_(sh, L);
    writeAmount_(sh, row, p.labelCol, p.amtCol, label, amount);
  } else {
    const s = findByName_(L.sections, req.section, 'section');
    if (s.width === 1) {
      const row = lastUsedRow_(sh, s.amtCol) + 1;
      sh.getRange(row, s.amtCol).setValue(amount).setNumberFormat(NUM_FORMAT);
    } else {
      const last = Math.max(lastUsedRow_(sh, s.col), lastUsedRow_(sh, s.amtCol));
      let row = label ? findLabelRow_(sh, s.col, 2, last, label) : 0;
      if (!row) row = firstEmptyPairRow_(sh, s.col, 2, last) || last + 1;
      writeAmount_(sh, row, s.col, s.amtCol, label || '—', amount);
    }
  }
  SpreadsheetApp.flush();
  afterChange_(d.y, d.m);
  invalidateFrom_(d.y, d.m);
  return sh.getName();
}

/**
 * {sheet, kind, section|person, row, label, oldAmount, amount|null}
 * Sets the amount of one entry (null = remove it). Refuses if the row no longer
 * holds the same entry (sheet edited meanwhile).
 */
function edit_(req) {
  const t = allMonths_().filter(function (x) { return x.name === req.sheet; })[0];
  if (!t) throw new Error('Month not found: ' + req.sheet);
  const sh = t.sheet, L = layout_(sh), row = Number(req.row);
  let labelCol, amtCol, amountOnly = false;
  if (req.kind === 'income') {
    const p = findByName_(L.persons, req.person, 'person');
    labelCol = p.labelCol; amtCol = p.amtCol;
  } else {
    const s = findByName_(L.sections, req.section, 'section');
    amountOnly = s.width === 1; labelCol = s.col; amtCol = s.amtCol;
  }
  const cell = sh.getRange(row, amtCol);
  const sameLabel = amountOnly || String(sh.getRange(row, labelCol).getValue()).trim() === String(req.label || '').trim();
  const sameAmount = req.oldAmount == null || Math.abs(Number(cell.getValue() || 0) - Number(req.oldAmount)) < 0.005;
  if (!sameLabel || !sameAmount) throw new Error('The sheet was changed meanwhile. Refresh and try again.');

  if (req.amount == null) {
    if (amountOnly) cell.deleteCells(SpreadsheetApp.Dimension.ROWS); // close the gap in the list
    else cell.clearContent();
  } else {
    if (!(Number(req.amount) > 0)) throw new Error('Amount must be positive');
    cell.setValue(Number(req.amount)).setNumberFormat(NUM_FORMAT);
  }
  SpreadsheetApp.flush();
  afterChange_(t.y, t.m);
  invalidateFrom_(t.y, t.m);
  return sh.getName();
}

// ---------- months & files ----------

/** Returns the tab for year/month, creating the tab (and the year file) if needed. */
function ensureMonth_(y, m) {
  const ss = yearFile_(y, true);
  const name = MONTHS[m - 1] + ' ' + y;
  let sh = ss.getSheetByName(name);
  if (sh) {
    if (!allMonths_().some(function (t) { return t.name === name; })) addToIndex_(name, y, m, ss.getId());
    return sh;
  }

  const tmpl = latestBefore_(y, m) || allMonths_()[0];
  if (!tmpl) throw new Error('No month tab to copy from');
  sh = tmpl.sheet.copyTo(ss).setName(name);
  ss.setActiveSheet(sh);
  ss.moveActiveSheet(ss.getNumSheets());
  // A brand-new file starts with an empty default tab: remove it.
  ss.getSheets().forEach(function (s) {
    if (s.getSheetId() !== sh.getSheetId() && !parseTabName_(s.getName()) && s.getLastRow() === 0) ss.deleteSheet(s);
  });

  clearAmounts_(sh);
  addToIndex_(name, y, m, ss.getId());
  ensureBalance_(sh, y, m);
  return sh;
}

/** Keeps reasons, sources and the fixed Кредит; clears every other amount. */
function clearAmounts_(sh) {
  const L = layout_(sh);
  const maxRow = Math.max(sh.getMaxRows(), 2);
  L.sections.forEach(function (s) {
    if (s.fixed) {
      if (maxRow > 2) sh.getRange(3, s.amtCol, maxRow - 2, 1).clearContent();
    } else {
      sh.getRange(2, s.amtCol, maxRow - 1, 1).clearContent();
    }
  });
  L.persons.forEach(function (p) {
    if (L.totalRow - INCOME_FIRST_ROW > 0) {
      sh.getRange(INCOME_FIRST_ROW, p.amtCol, L.totalRow - INCOME_FIRST_ROW, 1).clearContent();
    }
  });
}

function yearFile_(y, create) {
  const active = SpreadsheetApp.getActiveSpreadsheet();
  if (active && active.getName() === FILE_PREFIX + y) return active;
  const props = PropertiesService.getScriptProperties();
  const cached = props.getProperty('FILE_' + y);
  if (cached) {
    try { return SpreadsheetApp.openById(cached); } catch (e) { props.deleteProperty('FILE_' + y); }
  }
  const folder = folder_();
  const it = folder.getFilesByName(FILE_PREFIX + y);
  if (it.hasNext()) {
    const ss = SpreadsheetApp.openById(it.next().getId());
    props.setProperty('FILE_' + y, ss.getId());
    return ss;
  }
  if (!create) return null;
  const ss = SpreadsheetApp.create(FILE_PREFIX + y);
  DriveApp.getFileById(ss.getId()).moveTo(folder);
  props.setProperty('FILE_' + y, ss.getId());
  return ss;
}

function folder_() {
  const id = PropertiesService.getScriptProperties().getProperty('FOLDER_ID');
  if (id) return DriveApp.getFolderById(id);
  return DriveApp.getFileById(SpreadsheetApp.getActiveSpreadsheet().getId()).getParents().next();
}

// ---------- month index ----------
// The list of month tabs is kept in Script Properties, so a request doesn't have to scan
// the Drive folder. It is rebuilt by setup, by a manual refresh in the app (fresh=true),
// and automatically when a listed tab turns out to be renamed or deleted.

let MEMO_MONTHS = null;
const OPENED = {};

function openFile_(id) {
  if (!OPENED[id]) {
    const active = SpreadsheetApp.getActiveSpreadsheet();
    OPENED[id] = active && active.getId() === id ? active : SpreadsheetApp.openById(id);
  }
  return OPENED[id];
}

function monthRef_(x) {
  const ref = { name: x.n, y: x.y, m: x.m, fileId: x.f };
  let sheet = null;
  Object.defineProperty(ref, 'sheet', {
    get: function () {
      if (!sheet) {
        sheet = openFile_(x.f).getSheetByName(x.n);
        if (!sheet) {
          const err = new Error('Tab "' + x.n + '" was renamed or deleted');
          err.staleIndex = true;
          throw err;
        }
      }
      return sheet;
    },
  });
  return ref;
}

/** All month tabs in all year files, oldest first: [{name, y, m, fileId, sheet}] */
function allMonths_() {
  if (MEMO_MONTHS) return MEMO_MONTHS;
  const raw = PropertiesService.getScriptProperties().getProperty('MONTH_INDEX');
  const idx = raw ? JSON.parse(raw) : rebuildIndex_();
  MEMO_MONTHS = idx.map(monthRef_);
  return MEMO_MONTHS;
}

function rebuildIndex_() {
  const props = PropertiesService.getScriptProperties();
  const idx = [];
  const it = folder_().getFiles();
  while (it.hasNext()) {
    const f = it.next();
    const fname = f.getName();
    if (fname.indexOf(FILE_PREFIX) !== 0 || f.getMimeType() !== MimeType.GOOGLE_SHEETS) continue;
    const yr = Number(fname.slice(FILE_PREFIX.length));
    if (!yr) continue;
    props.setProperty('FILE_' + yr, f.getId());
    openFile_(f.getId()).getSheets().forEach(function (s) {
      const p = parseTabName_(s.getName());
      if (p) idx.push({ n: s.getName(), y: p.y, m: p.m, f: f.getId() });
    });
  }
  saveIndex_(idx);
  return idx;
}

function addToIndex_(name, y, m, fileId) {
  const idx = allMonths_().filter(function (t) { return t.name !== name; })
    .map(function (t) { return { n: t.name, y: t.y, m: t.m, f: t.fileId }; });
  idx.push({ n: name, y: y, m: m, f: fileId });
  saveIndex_(idx);
}

function saveIndex_(idx) {
  idx.sort(function (a, b) { return (a.y * 12 + a.m) - (b.y * 12 + b.m); });
  PropertiesService.getScriptProperties().setProperty('MONTH_INDEX', JSON.stringify(idx));
  MEMO_MONTHS = null;
}

function monthTabs_(ss) {
  if (!ss) return [];
  return ss.getSheets().map(function (s) {
    const p = parseTabName_(s.getName());
    return p ? { name: s.getName(), y: p.y, m: p.m, sheet: s } : null;
  }).filter(Boolean);
}

function latestBefore_(y, m) {
  const k = y * 12 + m;
  const before = allMonths_().filter(function (t) { return t.y * 12 + t.m < k; });
  return before[before.length - 1] || null;
}

function parseTabName_(name) {
  const mm = String(name).trim().match(/^(\S+)\s+(\d{4})$/);
  if (!mm) return null;
  const i = MONTHS.indexOf(mm[1]);
  return i < 0 ? null : { y: Number(mm[2]), m: i + 1 };
}

// ---------- layout ----------

/** Reads a whole month tab in two calls: all values, and the merges of row 1. */
function snap_(sh) {
  const vals = sh.getDataRange().getValues();
  const lastCol = vals.length ? vals[0].length : 1;
  const widths = {};
  sh.getRange(1, 1, 1, Math.max(lastCol, 1)).getMergedRanges().forEach(function (r) {
    if (r.getRow() === 1) widths[r.getColumn()] = r.getNumColumns();
  });
  return { sh: sh, vals: vals, widths: widths, lastRow: vals.length, lastCol: lastCol };
}

function cellOf_(S, r, c) {
  return r >= 1 && c >= 1 && r <= S.lastRow && c <= S.lastCol ? S.vals[r - 1][c - 1] : '';
}

/** Reads the structure of a month tab from its headers. */
function layout_(sh) { return layoutFrom_(snap_(sh)); }

function layoutFrom_(S) {
  const sections = [];
  let incomeCol = 0, balanceCol = 0, c = 1;
  while (c <= S.lastCol) {
    const h = String(cellOf_(S, 1, c)).trim();
    const w = S.widths[c] || 1;
    if (h === INCOME_HEADER) { incomeCol = c; c += w; continue; }
    if (h === BALANCE_HEADER) { balanceCol = c; c += w; continue; }
    if (h && !incomeCol && !balanceCol) {
      sections.push({ name: h, col: c, width: w, amtCol: c + w - 1, fixed: FIXED_SECTIONS.indexOf(h) >= 0 });
    }
    c += w;
  }
  if (!incomeCol) throw new Error('"' + INCOME_HEADER + '" not found in row 1 of ' + S.sh.getName());

  const incWidth = S.widths[incomeCol] || 4;
  const persons = [];
  for (let i = 0; i < incWidth; i += 2) {
    const n = String(cellOf_(S, 3, incomeCol + i)).trim();
    if (n) persons.push({ name: n, labelCol: incomeCol + i, amtCol: incomeCol + i + 1 });
  }
  let totalRow = 0;
  for (let r = INCOME_FIRST_ROW; r <= S.lastRow; r++) {
    if (String(cellOf_(S, r, incomeCol)).indexOf(TOTAL_PREFIX) === 0) { totalRow = r; break; }
  }
  if (!totalRow) throw new Error('Income total row ("' + TOTAL_PREFIX + ' …") not found in ' + S.sh.getName());

  return {
    sections: sections, persons: persons, incomeCol: incomeCol, incomeWidth: incWidth,
    totalRow: totalRow, balanceCol: balanceCol || incomeCol + incWidth + 1,
  };
}

/** The Баланс values, or null if the block isn't there yet. */
function balanceFrom_(S, L) {
  if (String(cellOf_(S, 1, L.balanceCol)).trim() !== BALANCE_HEADER) return null;
  const v = function (r) { return num_(cellOf_(S, r, L.balanceCol + 1)); };
  return { previous: v(B_PREV), income: v(B_INCOME), bills: v(B_BILLS), leftover: v(B_LEFT) };
}

// ---------- balance block ----------

/** Adds/refreshes the Баланс block. The previous leftover links to the previous tab. */
function ensureBalance_(sh, y, m) {
  const L = layout_(sh);
  const bc = L.balanceCol, vc = bc + 1;
  const V = function (r) { return a1_(r, vc); };

  if (String(sh.getRange(1, bc).getValue()).trim() !== BALANCE_HEADER) {
    sh.getRange(1, bc, 1, 2).merge().setValue(BALANCE_HEADER).setFontWeight('bold').setHorizontalAlignment('center');
  }
  sh.getRange(B_PREV, bc, 4, 1).setValues([
    ['Остатък от предходния месец'], ['Приходи'], ['Разходи'], ['Остатък за месеца']]);
  sh.getRange(B_LEFT, bc, 1, 2).setFontWeight('bold');

  // Income: sum of the "Общо …" cells in the Приходи block.
  const incomeParts = L.persons.map(function (p) {
    return 'SUMIF(' + col_(p.labelCol) + ':' + col_(p.labelCol) + ',"' + TOTAL_PREFIX + '*",' +
      col_(p.amtCol) + ':' + col_(p.amtCol) + ')';
  });
  sh.getRange(B_INCOME, vc).setFormula('=' + (incomeParts.join('+') || '0'));
  // Bills: every amount column of every section.
  const billParts = L.sections.map(function (s) { return col_(s.amtCol) + '2:' + col_(s.amtCol); });
  sh.getRange(B_BILLS, vc).setFormula('=SUM(' + billParts.join(',') + ')');
  sh.getRange(B_LEFT, vc).setFormula('=' + V(B_PREV) + '+' + V(B_INCOME) + '-' + V(B_BILLS));

  // Previous leftover: link to previous tab in the same file; copy the value across files.
  const prev = latestBefore_(y, m);
  const prevCell = sh.getRange(B_PREV, vc);
  if (prev && prev.sheet.getParent().getId() === sh.getParent().getId()) {
    const pb = layout_(prev.sheet).balanceCol + 1;
    prevCell.setFormula("='" + prev.name + "'!" + a1_(B_LEFT, pb));
  } else if (prev) {
    prevCell.setValue(readBalance_(prev.sheet, prev.y, prev.m).leftover);
  } else if (prevCell.getValue() === '') {
    prevCell.setValue(0); // first month: type the real leftover here if needed
  }

  // Per-section totals.
  sh.getRange(B_BY_SECTION, bc, 1, 2).merge().setValue('Разходи по категории').setFontWeight('bold');
  const rows = L.sections.map(function (s) {
    return [s.name, '=SUM(' + col_(s.amtCol) + '2:' + col_(s.amtCol) + ')'];
  });
  if (rows.length) sh.getRange(B_BY_SECTION + 1, bc, rows.length, 2).setValues(rows);

  sh.getRange(B_PREV, vc, B_BY_SECTION + rows.length, 1).setNumberFormat(NUM_FORMAT);
  sh.autoResizeColumn(bc);
}

function readBalance_(sh, y, m) {
  let S = snap_(sh);
  let b = balanceFrom_(S, layoutFrom_(S));
  if (!b) {
    ensureBalance_(sh, y, m);
    SpreadsheetApp.flush();
    S = snap_(sh);
    b = balanceFrom_(S, layoutFrom_(S));
  }
  return b;
}

/** After a change in December, refresh January's carried-over leftover in the next year's file. */
function afterChange_(y, m) {
  const nextY = m === 12 ? y + 1 : y, nextM = m === 12 ? 1 : m + 1;
  if (m !== 12) return;
  const ss = yearFile_(nextY, false);
  const sh = ss && ss.getSheetByName(MONTHS[nextM - 1] + ' ' + nextY);
  if (sh) ensureBalance_(sh, nextY, nextM);
}

// ---------- reading a month ----------

function monthData_(sh, y, m) {
  let S = snap_(sh);
  let L = layoutFrom_(S);
  let balance = balanceFrom_(S, L);
  if (!balance) { // first read of a tab made before this script: add the Баланс block
    ensureBalance_(sh, y, m);
    SpreadsheetApp.flush();
    S = snap_(sh);
    L = layoutFrom_(S);
    balance = balanceFrom_(S, L);
  }
  const items = function (labelCol, amtCol, from, to, amountOnly) {
    const out = [];
    for (let r = from; r <= to; r++) {
      const label = amountOnly ? '' : String(cellOf_(S, r, labelCol)).trim();
      const a = cellOf_(S, r, amtCol);
      if (label === '' && a === '') continue;
      out.push({ row: r, label: label, amount: a === '' ? null : num_(a) });
    }
    return out;
  };
  const total = function (list) { return num_(list.reduce(function (t, i) { return t + (i.amount || 0); }, 0)); };

  const sections = L.sections.map(function (s) {
    const list = items(s.col, s.amtCol, 2, S.lastRow, s.width === 1);
    return { name: s.name, amountOnly: s.width === 1, fixed: s.fixed, items: list, total: total(list) };
  });
  const persons = L.persons.map(function (p) {
    const list = items(p.labelCol, p.amtCol, INCOME_FIRST_ROW, L.totalRow - 1, false);
    return { name: p.name, items: list, total: total(list) };
  });
  return { sections: sections, persons: persons, balance: balance };
}

// ---------- cell helpers ----------

function writeAmount_(sh, row, labelCol, amtCol, label, amount) {
  const lc = sh.getRange(row, labelCol), ac = sh.getRange(row, amtCol);
  if (String(lc.getValue()).trim() === '') lc.setValue(label);
  const f = ac.getFormula(), v = ac.getValue();
  if (f) ac.setFormula(f + '+' + amount);                    // =12+8  → =12+8+5
  else if (v !== '' && v !== null) ac.setFormula('=' + v + '+' + amount);
  else ac.setValue(amount);
  ac.setNumberFormat(NUM_FORMAT);
}

function findLabelRow_(sh, col, from, to, label) {
  if (to < from) return 0;
  const want = label.toLowerCase();
  const vals = sh.getRange(from, col, to - from + 1, 1).getValues();
  for (let i = 0; i < vals.length; i++) {
    if (String(vals[i][0]).trim().toLowerCase() === want) return from + i;
  }
  return 0;
}

function firstEmptyPairRow_(sh, labelCol, from, to) {
  if (to < from) return 0;
  const vals = sh.getRange(from, labelCol, to - from + 1, 2).getValues();
  for (let i = 0; i < vals.length; i++) {
    if (vals[i][0] === '' && vals[i][1] === '') return from + i;
  }
  return 0;
}

function lastUsedRow_(sh, col) {
  const vals = sh.getRange(1, col, Math.max(sh.getLastRow(), 1), 1).getValues();
  for (let i = vals.length - 1; i >= 0; i--) if (vals[i][0] !== '') return i + 1;
  return 1;
}

/** Income block is full: push the total row down by one row (only inside the block). */
function insertIncomeRow_(sh, L) {
  const r = L.totalRow;
  sh.getRange(r, L.incomeCol, 1, L.incomeWidth).insertCells(SpreadsheetApp.Dimension.ROWS);
  L.persons.forEach(function (p) {
    sh.getRange(r + 1, p.amtCol).setFormula('=SUM(' + col_(p.amtCol) + INCOME_FIRST_ROW + ':' + col_(p.amtCol) + r + ')');
  });
  L.totalRow = r + 1;
  return r;
}

function findByName_(list, name, what) {
  const x = list.filter(function (i) { return i.name === name; })[0];
  if (!x) throw new Error('Unknown ' + what + ': ' + name);
  return x;
}

function parseDate_(s) {
  const p = String(s || '').split('-').map(Number);
  if (p.length !== 3 || !p[0] || !p[1]) {
    const n = new Date();
    return { y: n.getFullYear(), m: n.getMonth() + 1 };
  }
  return { y: p[0], m: p[1] };
}

function num_(v) { const n = Number(v); return isNaN(n) ? 0 : Math.round(n * 100) / 100; }

function col_(c) {
  let s = '';
  while (c > 0) { const r = (c - 1) % 26; s = String.fromCharCode(65 + r) + s; c = (c - r - 1) / 26; }
  return s;
}

function a1_(r, c) { return col_(c) + r; }

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
