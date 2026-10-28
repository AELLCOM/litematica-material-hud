/**
 * Material HUD -> Google Sheets: приёмник «таблицы команды» (Apps Script, веб-приложение).
 *
 * Установка (один раз, в таблице команды):
 *   1. Расширения -> Apps Script -> удалить всё в Code.gs -> вставить этот файл -> сохранить.
 *   2. Выбрать функцию setup -> Выполнить -> разрешить доступ. В журнале появится ключ (TOKEN).
 *   3. Развернуть -> Новое развёртывание -> тип «Веб-приложение»:
 *      «Выполнять как: я», «У кого есть доступ: все» -> Развернуть -> скопировать адрес (…/exec).
 *   4. В игре: Material HUD -> Схемы -> шестерёнка: «Таблица команды: адрес скрипта» = адрес,
 *      «Таблица команды: ключ» = TOKEN из журнала.
 * Каждая отправка из игры добавляет в эту таблицу новый лист и возвращает ссылку на него.
 *
 * Setup (once, in the team spreadsheet): Extensions -> Apps Script -> paste this file -> run setup()
 * (grants access, logs the TOKEN) -> Deploy -> New deployment -> Web app (Execute as: me,
 * Who has access: anyone) -> put the /exec URL and the TOKEN into the mod settings.
 *
 * Протокол 1: POST JSON {protocol, token, name, title, meta, labels{}, statuses[2], nicks[], parts[],
 * items:[{id, name, count, stack, breakdown, icon?}], raw?:[{name, count, stack, sources}], logsTotal?,
 * palette?{роль: '#rrggbb'}}. Палитра (с Material HUD 1.0.1) накладывается поверх цветов C по умолчанию;
 * без неё лист такой же, как раньше.
 * Ответ: {"ok":"yes","url":…,"sheet":…} или {"ok":"no","error":…}. Булевых значений нет нигде:
 * статусы — словами из выпадающего списка, пустая ячейка — «не взято».
 */

const PROTOCOL = 1;
const MIN_NICK_SLOTS = 12;
const SHULKER_SLOTS = 27;

// Канон Aell_ v6: фон знака, тройки цветов, 16 тонов ников (hue-table, ступень «верх»)
// Роли canvas, row, rowAlt, panel, text, chip по умолчанию не заданы: лист остаётся белым, как раньше
const C_DEFAULT = {
  violet: '#6d28d9', deep: '#3f1178', night: '#2a0b52', sub: '#e1c8ff', frame: '#e2e8ff',
  grid: '#8b5bf7', border: '#d6c6fc', done: '#dcfce7', taking: '#fff1d6',
  barTotal: '#7ab6ff', barDone: '#1d4ed8', white: '#ffffff'
};
let C = C_DEFAULT;
const NICK_TONES = ['#f9aac1', '#a0da3e', '#8eccf8', '#f9a6d5', '#dbc63d', '#5ed6f8', '#e9a8f8', '#f9b55b',
  '#46dddd', '#cdb7f8', '#f9b18a', '#46e1bf', '#bebdf8', '#f9aea6', '#46e77a', '#a7c5f8'];

// Подписи по умолчанию — если мод их не прислал
const LABELS = {
  'col.index': '№', 'col.item': 'Предмет', 'col.count': 'Всего, шт', 'col.breakdown': 'Разбивка (шалкеры + стаки + шт)',
  'col.who': 'Кто собирает', 'col.status': 'Статус', 'col.stack': 'Стак',
  'stats.title': 'Кто сколько собрал', 'stats.nick': 'Ник', 'stats.positions': 'Позиций', 'stats.blocks': 'Всего блоков',
  'stats.shulkers': 'Шалкеров', 'stats.done': 'Собрано, блоков', 'stats.ready': 'Готово', 'stats.total': 'Итого',
  'stats.all': 'Вся таблица', 'stats.free': 'Не взято', 'stats.legend': 'Статусы', 'stats.legendHint': 'пусто — не взято',
  'chart.title': 'Кто сколько собрал, блоков', 'raw.title': 'Крупное сырьё (по рецептам игры, для справки)',
  'raw.resource': 'Ресурс', 'raw.need': 'Нужно, шт', 'raw.shulkers': 'Шалкеров', 'raw.why': 'Зачем',
  'raw.logsTotal': 'Брёвна всего (все породы)', 'dv.who.error': 'Выберите ник из колонки «Ник»',
  'dv.status.error': 'Выберите статус из списка или оставьте пустым'
};

// Колонки листа (с 1) — та же разметка, что у xlsx из мода
const COL = { index: 1, icon: 2, item: 3, count: 4, breakdown: 5, who: 6, status: 7, stack: 8,
  nick: 10, positions: 11, blocks: 12, shulkers: 13, done: 14, ready: 15,
  resource: 17, need: 18, resShulkers: 19, why: 20 };
const WIDTHS = [40, 32, 280, 82, 215, 125, 90, 48, 20, 150, 75, 100, 96, 130, 75, 20, 230, 82, 76, 400];
const FIRST = 3;
const NICK_FIRST = 4;
const ROW_PX = 24;

function setup() {
  const props = PropertiesService.getScriptProperties();
  props.setProperty('SHEET_ID', SpreadsheetApp.getActive().getId());

  if (!props.getProperty('TOKEN')) {
    props.setProperty('TOKEN', Utilities.getUuid());
  }

  Logger.log('TOKEN для мода / for the mod: ' + props.getProperty('TOKEN'));
}

function doGet() {
  return reply({ ok: 'yes', app: 'material-hud-sheets', protocol: String(PROTOCOL) });
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);

  try {
    const req = JSON.parse(e.postData.contents);
    const props = PropertiesService.getScriptProperties();
    const token = props.getProperty('TOKEN');

    if (!token || req.token !== token) {
      return reply({ ok: 'no', error: 'token' });
    }

    if (Number(req.protocol) !== PROTOCOL) {
      return reply({ ok: 'no', error: 'protocol ' + req.protocol + ' (script ' + PROTOCOL + ')' });
    }

    C = Object.assign({}, C_DEFAULT, req.palette || {});
    const book = SpreadsheetApp.openById(props.getProperty('SHEET_ID'));
    const sheet = build(book, req);
    return reply({ ok: 'yes', url: book.getUrl() + '#gid=' + sheet.getSheetId(), sheet: sheet.getName() });
  } catch (err) {
    return reply({ ok: 'no', error: String(err && err.message ? err.message : err) });
  } finally {
    lock.releaseLock();
  }
}

function reply(object) {
  return ContentService.createTextOutput(JSON.stringify(object)).setMimeType(ContentService.MimeType.JSON);
}

function uniqueName(book, base) {
  const clean = String(base || 'Materials').replace(/[\[\]\*\?\/\\:]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 90) || 'Materials';
  let name = clean;

  for (let n = 2; book.getSheetByName(name); n++) {
    name = clean + ' (' + n + ')';
  }

  return name;
}

function letter(col) {
  let out = '';

  for (let n = col; n > 0; n = Math.floor((n - 1) / 26)) {
    out = String.fromCharCode(65 + (n - 1) % 26) + out;
  }

  return out;
}

function quoted(text) {
  return '"' + String(text).replace(/"/g, '""') + '"';
}

function build(book, req) {
  const L = Object.assign({}, LABELS, req.labels || {});
  const items = req.items || [];
  const n = Math.max(1, items.length);
  const end = FIRST + n - 1;
  const nicks = (req.nicks || []).map(String);
  const slots = Math.max(MIN_NICK_SLOTS, nicks.length);
  const nickLast = NICK_FIRST + slots - 1;
  const totalRow = NICK_FIRST + slots;
  const legendRow = totalRow + 4;
  const chartRow = legendRow + 2;
  const parts = req.parts || [];
  const raw = parts.indexOf('raw') >= 0 ? (req.raw || []) : [];
  const statuses = (req.statuses && req.statuses.length === 2) ? req.statuses.map(String) : ['Собираю', 'Собрано'];
  const sheet = book.insertSheet(uniqueName(book, req.name), book.getSheets().length);

  const neededRows = Math.max(end, chartRow + 24, NICK_FIRST + raw.length + 3);

  if (sheet.getMaxRows() < neededRows) {
    sheet.insertRowsAfter(sheet.getMaxRows(), neededRows - sheet.getMaxRows());
  }

  if (sheet.getMaxColumns() < COL.why) {
    sheet.insertColumnsAfter(sheet.getMaxColumns(), COL.why - sheet.getMaxColumns());
  }

  // Фон и текст листа целиком — до всех блоков, блоки красят своё поверх
  if (C.canvas || C.text) {
    const all = sheet.getRange(1, 1, sheet.getMaxRows(), sheet.getMaxColumns());
    if (C.canvas) all.setBackground(C.canvas);
    if (C.text) all.setFontColor(C.text);
  }

  const R = c => '$' + letter(c) + '$' + FIRST + ':$' + letter(c) + '$' + end;
  const WHO = R(COL.who), STATUS = R(COL.status), COUNT = R(COL.count), STACK = R(COL.stack), ITEM = R(COL.item);
  const DONE = quoted(statuses[1]);

  // Шапка
  sheet.getRange(1, COL.index, 1, COL.stack).merge().setValue(req.title || req.name)
    .setFontFamily('Press Start 2P').setFontSize(12).setFontColor(C.white).setBackground(C.violet)
    .setVerticalAlignment('middle');
  sheet.getRange(1, COL.nick).setValue(req.meta || '').setFontFamily('JetBrains Mono').setFontSize(9).setFontColor(C.grid);
  sheet.getRange(2, COL.index, 1, 8).setValues([[L['col.index'], '', L['col.item'], L['col.count'], L['col.breakdown'],
    L['col.who'], L['col.status'], L['col.stack']]])
    .setFontWeight('bold').setFontColor(C.white).setBackground(C.deep).setHorizontalAlignment('center')
    .setVerticalAlignment('middle').setWrap(true);

  // Строки списка
  if (items.length > 0) {
    sheet.getRange(FIRST, COL.index, items.length, 8).setValues(items.map((it, i) =>
      [i + 1, '', String(it.name), Number(it.count), String(it.breakdown || ''), '', '', Number(it.stack) || 64]));
  }

  const body = sheet.getRange(FIRST, COL.index, n, 8);
  body.setBorder(true, true, true, true, true, true, C.border, SpreadsheetApp.BorderStyle.SOLID).setVerticalAlignment('middle');
  if (C.row) body.setBackground(C.row);
  sheet.getRange(FIRST, COL.index, n, 1).setHorizontalAlignment('center').setFontFamily('JetBrains Mono');
  sheet.getRange(FIRST, COL.count, n, 1).setNumberFormat('#,##0').setFontFamily('JetBrains Mono');
  sheet.getRange(FIRST, COL.who, n, 2).setHorizontalAlignment('center');
  sheet.getRange(FIRST, COL.stack, n, 1).setHorizontalAlignment('center');

  if (parts.indexOf('icons') >= 0) {
    putIcons(sheet, items);
  }

  // Кто сколько собрал
  sheet.getRange(2, COL.nick, 1, 6).merge().setValue(L['stats.title']).setFontWeight('bold').setFontSize(12)
    .setFontColor(C.white).setBackground(C.deep).setHorizontalAlignment('center');
  sheet.getRange(FIRST, COL.nick, 1, 6).setValues([[L['stats.nick'], L['stats.positions'], L['stats.blocks'],
    L['stats.shulkers'], L['stats.done'], L['stats.ready']]])
    .setFontWeight('bold').setFontColor(C.night).setBackground(C.sub).setHorizontalAlignment('center').setWrap(true);

  const nickValues = [], fills = [], formulas = [];

  for (let k = 0; k < slots; k++) {
    const r = NICK_FIRST + k, N = '$' + letter(COL.nick) + r, E = 'IF(' + N + '="","",';
    nickValues.push([k < nicks.length ? nicks[k] : '']);
    fills.push([NICK_TONES[k % NICK_TONES.length]]);
    formulas.push([
      '=' + E + 'COUNTIF(' + WHO + ',' + N + '))',
      '=' + E + 'SUMIF(' + WHO + ',' + N + ',' + COUNT + '))',
      '=' + E + 'SUMPRODUCT((' + WHO + '=' + N + ')*' + COUNT + '/(' + SHULKER_SLOTS + '*' + STACK + ')))',
      '=' + E + 'SUMIFS(' + COUNT + ',' + WHO + ',' + N + ',' + STATUS + ',' + DONE + '))',
      '=IF(OR(' + N + '="",' + letter(COL.blocks) + r + '=0),"",' + letter(COL.done) + r + '/' + letter(COL.blocks) + r + ')'
    ]);
  }

  if (C.panel) sheet.getRange(NICK_FIRST, COL.positions, slots, 5).setBackground(C.panel);
  const nickCells = sheet.getRange(NICK_FIRST, COL.nick, slots, 1).setValues(nickValues).setBackgrounds(fills).setFontWeight('bold');
  if (C.chip) nickCells.setFontColor(C.chip);
  sheet.getRange(NICK_FIRST, COL.positions, slots, 5).setFormulas(formulas).setFontFamily('JetBrains Mono');
  sheet.getRange(NICK_FIRST, COL.positions, slots, 2).setNumberFormat('#,##0');
  sheet.getRange(NICK_FIRST, COL.shulkers, slots, 1).setNumberFormat('0.0');
  sheet.getRange(NICK_FIRST, COL.done, slots, 1).setNumberFormat('#,##0');
  sheet.getRange(NICK_FIRST, COL.ready, slots, 1).setNumberFormat('0%');

  const sum = c => '=SUM(' + letter(c) + NICK_FIRST + ':' + letter(c) + nickLast + ')';
  const ready = r => '=IF(' + letter(COL.blocks) + r + '=0,"",' + letter(COL.done) + r + '/' + letter(COL.blocks) + r + ')';
  sheet.getRange(totalRow, COL.nick, 3, 1).setValues([[L['stats.total']], [L['stats.all']], [L['stats.free']]]);
  sheet.getRange(totalRow, COL.positions, 2, 5).setFormulas([
    [sum(COL.positions), sum(COL.blocks), sum(COL.shulkers), sum(COL.done), ready(totalRow)],
    ['=COUNTA(' + ITEM + ')', '=SUM(' + COUNT + ')', '=SUMPRODUCT(' + COUNT + '/(' + SHULKER_SLOTS + '*' + STACK + '))',
      '=SUMIF(' + STATUS + ',' + DONE + ',' + COUNT + ')', ready(totalRow + 1)]
  ]);
  sheet.getRange(totalRow + 2, COL.positions, 1, 3).setFormulas([[
    '=COUNTBLANK(' + WHO + ')', '=SUMPRODUCT((' + WHO + '="")*' + COUNT + ')',
    '=SUMPRODUCT((' + WHO + '="")*' + COUNT + '/(' + SHULKER_SLOTS + '*' + STACK + '))']]);
  const totals = sheet.getRange(totalRow, COL.nick, 3, 6);
  totals.setBackground(C.frame).setFontWeight('bold').setFontColor(C.night);
  sheet.getRange(totalRow, COL.positions, 3, 5).setFontFamily('JetBrains Mono');
  sheet.getRange(totalRow, COL.positions, 3, 2).setNumberFormat('#,##0');
  sheet.getRange(totalRow, COL.shulkers, 3, 1).setNumberFormat('0.0');
  sheet.getRange(totalRow, COL.done, 3, 1).setNumberFormat('#,##0');
  sheet.getRange(totalRow, COL.ready, 3, 1).setNumberFormat('0%');
  sheet.getRange(FIRST, COL.nick, totalRow + 3 - FIRST, 6)
    .setBorder(true, true, true, true, true, true, C.border, SpreadsheetApp.BorderStyle.SOLID);

  // Легенда статусов — она же источник выпадающего списка «Статус»
  sheet.getRange(legendRow, COL.nick).setValue(L['stats.legend']).setFontWeight('bold').setFontColor(C.night)
    .setBackground(C.sub).setHorizontalAlignment('center');
  sheet.getRange(legendRow, COL.positions, 1, 2).setValues([[statuses[0], statuses[1]]])
    .setBackgrounds([[C.taking, C.done]]).setHorizontalAlignment('center');
  sheet.getRange(legendRow, COL.shulkers).setValue(L['stats.legendHint']).setFontFamily('JetBrains Mono')
    .setFontSize(9).setFontColor(C.grid);

  // Выпадающие списки — из диапазонов листа
  sheet.getRange(FIRST, COL.who, n, 1).setDataValidation(SpreadsheetApp.newDataValidation()
    .requireValueInRange(sheet.getRange(NICK_FIRST, COL.nick, slots, 1), true)
    .setAllowInvalid(false).setHelpText(L['dv.who.error']).build());
  sheet.getRange(FIRST, COL.status, n, 1).setDataValidation(SpreadsheetApp.newDataValidation()
    .requireValueInRange(sheet.getRange(legendRow, COL.positions, 1, 2), true)
    .setAllowInvalid(false).setHelpText(L['dv.status.error']).build());

  // Условные цвета: ник — своим тоном, строка — «Собрано» зелёным, «Собираю»/взято — янтарным
  const rules = [];
  const who = sheet.getRange(FIRST, COL.who, n, 1);
  const rows = sheet.getRange(FIRST, COL.index, n, COL.status);
  const W = '$' + letter(COL.who) + FIRST, S = '$' + letter(COL.status) + FIRST;

  for (let k = 0; k < slots; k++) {
    let rule = SpreadsheetApp.newConditionalFormatRule()
      .whenFormulaSatisfied('=AND(' + W + '<>"",' + W + '=$' + letter(COL.nick) + '$' + (NICK_FIRST + k) + ')')
      .setBackground(NICK_TONES[k % NICK_TONES.length]).setBold(true).setRanges([who]);
    if (C.chip) rule = rule.setFontColor(C.chip);
    rules.push(rule.build());
  }

  rules.push(SpreadsheetApp.newConditionalFormatRule().whenFormulaSatisfied('=' + S + '=' + DONE)
    .setBackground(C.done).setRanges([rows]).build());
  rules.push(SpreadsheetApp.newConditionalFormatRule()
    .whenFormulaSatisfied('=OR(' + S + '=' + quoted(statuses[0]) + ',AND(' + W + '<>"",' + S + '=""))')
    .setBackground(C.taking).setRanges([rows]).build());

  // Чередование строк — последним правилом: статусы и ники главнее
  if (C.rowAlt) {
    rules.push(SpreadsheetApp.newConditionalFormatRule().whenFormulaSatisfied('=MOD(ROW(),2)=0')
      .setBackground(C.rowAlt).setRanges([rows]).build());
  }

  sheet.setConditionalFormatRules(rules);

  // Крупное сырьё
  if (raw.length > 0) {
    sheet.getRange(2, COL.resource, 1, 4).merge().setValue(L['raw.title']).setFontWeight('bold').setFontSize(12)
      .setFontColor(C.white).setBackground(C.deep).setHorizontalAlignment('center');
    sheet.getRange(FIRST, COL.resource, 1, 4).setValues([[L['raw.resource'], L['raw.need'], L['raw.shulkers'], L['raw.why']]])
      .setFontWeight('bold').setFontColor(C.night).setBackground(C.sub).setHorizontalAlignment('center');
    const values = raw.map(x => [String(x.name), Number(x.count), '', String(x.sources || '')]);
    const rawBody = sheet.getRange(NICK_FIRST, COL.resource, raw.length, 4).setValues(values);
    if (C.panel) rawBody.setBackground(C.panel);
    sheet.getRange(NICK_FIRST, COL.resShulkers, raw.length, 1).setFormulas(raw.map((x, i) =>
      ['=' + letter(COL.need) + (NICK_FIRST + i) + '/(' + SHULKER_SLOTS + '*' + (Number(x.stack) || 64) + ')']));
    sheet.getRange(NICK_FIRST, COL.need, raw.length, 1).setNumberFormat('#,##0').setFontFamily('JetBrains Mono');
    sheet.getRange(NICK_FIRST, COL.resShulkers, raw.length, 1).setNumberFormat('0.0').setFontFamily('JetBrains Mono');
    sheet.getRange(FIRST, COL.resource, raw.length + 1, 4)
      .setBorder(true, true, true, true, true, true, C.border, SpreadsheetApp.BorderStyle.SOLID);

    if (Number(req.logsTotal) > 0) {
      const r = NICK_FIRST + raw.length + 1;
      sheet.getRange(r, COL.resource, 1, 2).setValues([[L['raw.logsTotal'], Number(req.logsTotal)]]);
      sheet.getRange(r, COL.resShulkers).setFormula('=' + letter(COL.need) + r + '/(' + SHULKER_SLOTS + '*64)');
      sheet.getRange(r, COL.resource, 1, 4).setBackground(C.frame).setFontWeight('bold');
      sheet.getRange(r, COL.need).setNumberFormat('#,##0');
      sheet.getRange(r, COL.resShulkers).setNumberFormat('0.0');
    }
  }

  // Диаграмма «Кто сколько собрал, блоков»: всего и собрано по каждому нику
  if (parts.indexOf('chart') >= 0) {
    sheet.insertChart(sheet.newChart().asBarChart()
      .addRange(sheet.getRange(FIRST, COL.nick, slots + 1, 1))
      .addRange(sheet.getRange(FIRST, COL.blocks, slots + 1, 1))
      .addRange(sheet.getRange(FIRST, COL.done, slots + 1, 1))
      .setNumHeaders(1)
      .setTitle(L['chart.title'])
      .setColors([C.barTotal, C.barDone])
      .setOption('backgroundColor', C.panel || '#ffffff')
      .setOption('titleTextStyle', { color: C.text || '#000000' })
      .setOption('legend', { position: 'bottom', textStyle: { color: C.text || '#000000' } })
      .setOption('hAxis', { textStyle: { color: C.text || '#000000' }, gridlines: { color: C.border } })
      .setOption('vAxis', { textStyle: { color: C.text || '#000000' } })
      .setLegendPosition(Charts.Position.BOTTOM)
      .setOption('series', { 1: { dataLabel: 'value' } })
      .setPosition(chartRow, COL.nick, 0, 0)
      .build());
  }

  // Вид
  sheet.setFrozenRows(2);
  WIDTHS.forEach((w, i) => sheet.setColumnWidth(i + 1, w));
  sheet.hideColumns(COL.stack);
  sheet.setRowHeight(1, 40);
  sheet.setRowHeight(2, 40);
  sheet.setRowHeights(FIRST, Math.max(n, nickLast - FIRST + 1), ROW_PX);
  sheet.setTabColor(C.violet);
  sheet.getRange(2, COL.index, n + 1, COL.status).createFilter();
  return sheet;
}

/**
 * Иконки предметов. Сначала — картинки в ячейках (CellImage из data-URI PNG; так делают
 * многие скрипты, но в справочнике Google про data-URI прямо не сказано), при ошибке —
 * картинки поверх ячеек (Sheet.insertImage из Blob — описано в справочнике).
 */
function putIcons(sheet, items) {
  if (items.length === 0) {
    return;
  }

  try {
    const values = items.map(it => [it.icon
      ? SpreadsheetApp.newCellImage().setSourceUrl('data:image/png;base64,' + it.icon).setAltTextTitle(String(it.id)).build()
      : '']);
    sheet.getRange(FIRST, COL.icon, items.length, 1).setValues(values);
  } catch (err) {
    items.forEach((it, i) => {
      if (!it.icon) {
        return;
      }

      const blob = Utilities.newBlob(Utilities.base64Decode(it.icon), 'image/png', String(it.id) + '.png');
      const image = sheet.insertImage(blob, COL.icon, FIRST + i, 6, 2);
      image.setWidth(20).setHeight(20);
    });
  }
}
