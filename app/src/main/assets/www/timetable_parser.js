/*
 * 课表解析脚本（由 HomeFragment.injectParser 注入到教务 WebView 执行）。
 * 放在 assets 里是为了避免 Java 字符串转义，便于维护与排查。
 * 关键点：晚上 9-10 节的课程最容易漏，节次归属按「sections 首节号」优先，
 * 其次行标题节号，最后按标签行顺序兜底。
 */
(function () {
    var DAY_DEFAULT = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    var SLOT_KEYS = ['第一二节', '第三四节', '第五六节', '第七八节', '第九十节', '第十一十二节'];
    var CN_NUM = {
        '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6,
        '七': 7, '八': 8, '九': 9, '十': 10, '十一': 11, '十二': 12
    };

    var QUIET = false;   // 延时复检时静默，避免刷屏

    function log(msg) {
        if (QUIET) return;
        try {
            if (AndroidBack && AndroidBack.onTimetableLog) {
                AndroidBack.onTimetableLog(msg);
            }
        } catch (e) {}
    }

    function err(msg) {
        try {
            if (AndroidBack && AndroidBack.onError) {
                AndroidBack.onError(msg);
            }
        } catch (e) {}
    }

    /** 从任意文本取节号：先数字（跳过 08:00 这类时间串），再中文数字。兼容「第九、十节」「09~10小节」「第9-10节」 */
    function pickNum(s) {
        s = String(s || '');
        var m1 = s.match(/第\s*(\d{1,2})/);
        if (m1) {
            var a = parseInt(m1[1], 10);
            if (a >= 1 && a <= 12) return a;
        }
        var clean = s.replace(/\d{1,2}:\d{2}/g, ' ');
        var m2 = clean.match(/\d{1,2}/);
        if (m2) {
            var b = parseInt(m2[0], 10);
            if (b >= 1 && b <= 12) return b;
        }
        // 中文数字：优先匹配「十一」「十二」，避免被「十」截断成 10
        if (s.indexOf('十一') >= 0) return 11;
        if (s.indexOf('十二') >= 0) return 12;
        for (var i = 0; i < s.length; i++) {
            var c = CN_NUM[s.charAt(i)];
            if (c) return c;
        }
        return 0;
    }

    /** 节号 → 大节名（9、10 节归「第九十节」，11、12 节归「第十一十二节」） */
    function slotKeyOf(n) {
        if (n >= 1 && n <= 12) {
            var idx = Math.floor((n - 1) / 2);
            if (idx > 5) idx = 5;
            return SLOT_KEYS[idx];
        }
        return '';
    }

    function cleanText(s) {
        return String(s || '').replace(/\s+/g, ' ').trim();
    }

    /**
     * 表头文本 → 标准化星期（星期四 / 周四 / 四 都归一）。
     * 必须是「短标签」：课程 cell 里的 tooltip 文本常包含「周一…第1-2节」，不能被当成表头。
     */
    function normDay(txt) {
        var t = cleanText(txt);
        if (t.length > 12) return '';
        if (t.indexOf('节') >= 0 || t.indexOf('周次') >= 0 || t.indexOf('[') >= 0) return '';
        t = t.replace('星期', '周');
        var m = t.match(/^周([一二三四五六日天])/);
        if (m) return '周' + (m[1] === '天' ? '日' : m[1]);
        var m2 = t.match(/^([一二三四五六日天])(\s|$)/);
        if (m2) return '周' + (m2[1] === '天' ? '日' : m2[1]);
        return '';
    }

    /**
     * 解析表头：按「真实列号」建立 列号 → 星期 的映射。
     * 不能假设第 0 列一定是节次角标——有的模板有 2 个前导列（节次 + 时间），
     * 或者角标带 colspan，按顺序推算会把整行课程错位一天（周四显示到周三）。
     */
    function parseHeaders(table) {
        var cols = [];   // [{col, day, date}]
        var headRow = null;

        // 找「表头行」：至少 3 个单元格能识别成星期，优先 thead；避免把课程名里的「周三」当表头
        var cand = [];
        var thRows = table.querySelectorAll('thead tr');
        for (var t0 = 0; t0 < thRows.length; t0++) cand.push(thRows[t0]);
        var allRows = table.querySelectorAll('tr');
        for (var t1 = 0; t1 < allRows.length; t1++) cand.push(allRows[t1]);

        for (var r = 0; r < cand.length && !headRow; r++) {
            var cells = cand[r].children;
            var dayCnt = 0;
            for (var c = 0; c < cells.length; c++) {
                var tag = String(cells[c].tagName || '').toLowerCase();
                if (tag !== 'th' && tag !== 'td') continue;
                if (normDay(cells[c].textContent)) dayCnt++;
            }
            if (dayCnt >= 3) headRow = cand[r];
        }
        if (!headRow) return cols;

        var ci = 0;
        var cells2 = headRow.children;
        for (var i = 0; i < cells2.length; i++) {
            var cell = cells2[i];
            var tag2 = String(cell.tagName || '').toLowerCase();
            var cs = parseInt(cell.getAttribute('colspan') || '1', 10);
            if (cs !== cs || cs < 1) cs = 1;
            if (tag2 !== 'th' && tag2 !== 'td') { ci += cs; continue; }
            var day = normDay(cell.textContent);
            if (day) {
                var spans = cell.querySelectorAll('span');
                var date = spans.length >= 2 ? cleanText((spans[2] || spans[1]).textContent) : '';
                // 同一列号只保留第一个（有的表头有两行，第二行是日期）
                var dup = false;
                for (var k = 0; k < cols.length; k++) {
                    if (cols[k].col === ci) { dup = true; break; }
                }
                if (!dup) cols.push({ col: ci, day: day, date: date });
            }
            ci += cs;
        }
        return cols;
    }

    /** 取一个单元格内的所有课程条目，兼容 ul.courselists / li.courselists-item / 裸 div.qz-hasCourse-title 三种结构 */
    function collectItems(td) {
        var items = [];
        var uls = td.querySelectorAll('ul.courselists');
        for (var u = 0; u < uls.length; u++) {
            var lis = uls[u].querySelectorAll('li.courselists-item');
            for (var l = 0; l < lis.length; l++) items.push(lis[l]);
        }
        if (items.length) return items;
        var loose = td.querySelectorAll('li.courselists-item');
        for (var i = 0; i < loose.length; i++) items.push(loose[i]);
        if (items.length) return items;
        // 少数模板直接把标题 div 挂在 td 下
        var titles = td.querySelectorAll('div.qz-hasCourse-title');
        for (var t = 0; t < titles.length; t++) items.push(titles[t]);
        return items;
    }

    function parseDetails(node, tooltip) {
        var info = { teacher: '', location: '', weekRange: '', sections: '' };
        function read(divs) {
            for (var i = 0; i < divs.length; i++) {
                var text = cleanText(divs[i].textContent);
                if (text.indexOf('教师：') === 0) {
                    if (!info.teacher) info.teacher = text.replace('教师：', '');
                } else if (text.indexOf('上课地点：') === 0) {
                    if (!info.location) info.location = text.replace('上课地点：', '');
                } else if (text.indexOf('节次：') === 0) {
                    if (!info.sections) info.sections = text.replace('节次：', '');
                } else if (text.indexOf('周次：') === 0) {
                    if (!info.weekRange) info.weekRange = text.replace('周次：', '');
                }
            }
        }
        if (tooltip) {
            var tips = tooltip.querySelectorAll('div.qz-tooltipContent-detailitem');
            if (!tips.length) tips = tooltip.querySelectorAll('div');
            read(tips);
        }
        var own = node.querySelectorAll('div.qz-hasCourse-detailitem');
        read(own);
        if (!info.location && own.length) {
            // 有的模板地点是纯文本，不带「上课地点：」前缀
            for (var k = 0; k < own.length; k++) {
                var tv = cleanText(own[k].textContent);
                if (tv.indexOf('小节') >= 0 || tv.charAt(0) === '[') continue;
                if (tv.indexOf('教师') >= 0 || tv.indexOf('周次') >= 0 || tv.indexOf('节次') >= 0) continue;
                info.location = tv;
            }
        }
        var bl = info.weekRange.indexOf('[');
        var br = info.weekRange.indexOf(']');
        if (bl >= 0 && br > bl) info.weekRange = info.weekRange.slice(bl, br + 1);
        return info;
    }

    /**
     * 按真实网格列号展开每一行的单元格。
     * 处理 colspan / rowspan，避免「某行少一个单元格」时整行日期错位。
     * 传入 table 时忽略嵌套表格里的行（否则嵌套行的合并信息会污染外层列号）。
     */
    function buildGrid(rows, table) {
        var occ = [];     // occ[col] = 还占用多少行
        var out = [];
        for (var r = 0; r < rows.length; r++) {
            var row = rows[r];
            var res = [];
            if (table && row.parentNode && row.parentNode.closest) {
                var pt = row.parentNode.closest('table');
                if (pt && pt !== table) { out.push(res); continue; }   // 嵌套表格的行
            }
            var newSpans = [];
            var col = 0;
            var children = row.children;
            for (var i = 0; i < children.length; i++) {
                var cell = children[i];
                var tag = String(cell.tagName || '').toLowerCase();
                if (tag !== 'td' && tag !== 'th') continue;
                while (occ[col] > 0) col++;
                var cs = parseInt(cell.getAttribute('colspan') || '1', 10);
                if (isNaN(cs) || cs < 1) cs = 1;
                var rs = parseInt(cell.getAttribute('rowspan') || '1', 10);
                if (isNaN(rs) || rs < 1) rs = 1;
                res.push({ cell: cell, col: col, cs: cs });
                if (rs > 1) newSpans.push([col, cs, rs - 1]);
                col += cs;
            }
            for (var c1 = 0; c1 < occ.length; c1++) {
                if (occ[c1] > 0) occ[c1]--;
            }
            for (var s1 = 0; s1 < newSpans.length; s1++) {
                for (var k1 = 0; k1 < newSpans[s1][1]; k1++) {
                    occ[newSpans[s1][0] + k1] = newSpans[s1][2];
                }
            }
            out.push(res);
        }
        return out;
    }

    function parseTable(table) {
        var courses = [];
        var dayCols = parseHeaders(table);   // 表头识别到的星期（按列号升序）

        var allRows = table.querySelectorAll('tr');
        // 按真实列号展开（处理 colspan/rowspan），保证前导列/合并单元格不会把整行错位
        var grid = buildGrid(allRows, table);

        // 课表正文的实际网格宽度（只看有 3 个以上单元格的行，避开标题/说明行）
        var maxCols = 0;
        for (var mr = 0; mr < allRows.length; mr++) {
            var mCells = grid[mr] || [];
            if (mCells.length < 3) continue;
            var lastCell = mCells[mCells.length - 1];
            var w = lastCell.col + lastCell.cs;
            if (w > maxCols) maxCols = w;
        }

        // 星期列对齐：教务表头可能比正文少/多一个前导列（节次、时间），不能直接拿表头列号当正文列号，
        // 否则整行会错一天、甚至某一天整列课程被丢掉。改为按表头识别的星期数，取正文最右侧 N 列依次对应周一..周日。
        var dayByCol = {};
        var startCol = 0;   // 正文里星期列的起始列号（左边是节次/时间等前导列）
        if (maxCols >= 3) {
            var nDays = dayCols.length ? dayCols.length : DAY_DEFAULT.length;
            if (nDays > maxCols) nDays = maxCols;
            startCol = maxCols - nDays;
            for (var dj = 0; dj < nDays; dj++) {
                var nm = dayCols[dj] ? dayCols[dj].day : DAY_DEFAULT[dj];
                if (!nm) continue;
                dayByCol[startCol + dj] = {
                    col: startCol + dj,
                    day: nm,
                    date: dayCols[dj] ? dayCols[dj].date : ''
                };
            }
        } else {
            // 表结构异常：退回「第 1~7 列依次是周一到周日」
            for (var d0 = 0; d0 < DAY_DEFAULT.length; d0++) {
                dayByCol[d0 + 1] = { col: d0 + 1, day: DAY_DEFAULT[d0], date: '' };
            }
        }
        log('[JS] 表头列: ' + (dayCols.length ? dayCols.map(function (h) { return h.col + '=' + h.day; }).join(' ') : '无')
            + ' | 正文列(宽' + maxCols + '): '
            + Object.keys(dayByCol).sort(function (a, b) { return a - b; })
                .map(function (c) { return c + '=' + dayByCol[c].day; }).join(' '));

        var slotRows = [];
        var rowLog = [];
        var rowDiag = [];  // 行结构诊断：行号:单元格数(+跳过原因)，排查不同模板时看这行
        var seq = 0;      // 带节次文字的行序号，用于顺序兜底
        var rowOrd = 0;   // 正文行序号：没有节次列时按行序归节次
        var lastTi = -1;  // 合并行（无标题）沿用上一行

        Array.prototype.forEach.call(allRows, function (row, rowIdx) {
            var cells = grid[rowIdx] || [];
            if (!cells.length) { rowDiag.push(rowIdx + ':0'); return; }
            // 表头行：>=3 个「短星期标签」格，且这一行没有任何课程（课程 tooltip 里也常带「周一」字样）
            var dayCnt0 = 0;
            for (var dc0 = 0; dc0 < cells.length; dc0++) {
                if (normDay(cells[dc0].cell.textContent)) dayCnt0++;
            }
            if (dayCnt0 >= 3) {
                var rowHasCourse = false;
                for (var hc = 0; hc < cells.length && !rowHasCourse; hc++) {
                    if (dayByCol[cells[hc].col] && collectItems(cells[hc].cell).length) rowHasCourse = true;
                }
                if (!rowHasCourse) { rowDiag.push(rowIdx + ':' + cells.length + '×head'); return; }
            }

            // 行标题格：列号最小且不在星期列上的单元格（节次 / 时间等前导列）
            var titleCell = null;
            for (var tc = 0; tc < cells.length; tc++) {
                if (!dayByCol[cells[tc].col]) { titleCell = cells[tc].cell; break; }
            }
            // 行标题：优先 index-title，其次首列文本（保留时间串，便于按时间归节次）
            var titleDiv = titleCell ? titleCell.querySelector('div.index-title') : null;
            var raw = titleDiv ? cleanText(titleDiv.textContent) : '';
            if (!raw && titleCell) raw = cleanText(titleCell.textContent);

            var ti = -1;
            // 先按节号判定（晚九/第十一十二节也能正确归位），没有再按「晚」字样兜底
            var pn = pickNum(raw);
            if (pn) ti = Math.min(5, Math.floor((pn - 1) / 2));
            if (ti < 0 && raw.indexOf('晚') >= 0) ti = 4;
            // 只有时间没有节号（如「16:20-18:00」）：按开始时间归大节，19 点后算晚课
            if (ti < 0) {
                var tm = raw.match(/(\d{1,2}):\d{2}/);
                if (tm) {
                    var h = parseInt(tm[1], 10);
                    if (h >= 21) ti = 5;
                    else if (h >= 19) ti = 4;
                    else if (h >= 16) ti = 3;
                    else if (h >= 14) ti = 2;
                    else if (h >= 10) ti = 1;
                    else if (h >= 0) ti = 0;
                }
            }
            if (ti < 0) {
                for (var s = 0; s < SLOT_KEYS.length; s++) {
                    if (raw === SLOT_KEYS[s]) { ti = s; break; }
                }
            }
            if (ti < 0 && raw) ti = Math.min(SLOT_KEYS.length - 1, seq);
            if (ti < 0 && !raw) {
                // 合并行（节次格 rowspan 跨下来）：沿用上一行的节次
                if (startCol > 0 && lastTi >= 0) ti = lastTi;
                // 没有节次列（只有星期列）的模板：按正文行序归到第一二节~第十一十二节
                else ti = Math.min(SLOT_KEYS.length - 1, rowOrd);
            }
            if (cells.length >= 3) rowOrd++;
            if (raw) seq++;
            if (ti >= 0) lastTi = ti;

            var rowSlot = ti >= 0 ? SLOT_KEYS[ti] : '';
            var rowCount = 0;
            slotRows.push((rowSlot || ('?行' + seq)) + '[' + raw.slice(0, 8) + ']');

            for (var di = 0; di < cells.length; di++) {
                var td = cells[di].cell;
                var dayInfo = dayByCol[cells[di].col];
                if (!dayInfo) continue;   // 节次/时间等前导列
                var items = collectItems(td);
                if (!items.length) continue;

                // tooltip 挂在单元格上，个别模板挂到行上，做两级兜底
                var tooltip = td.querySelector('div.qz-tooltip');
                if (!tooltip) tooltip = row.querySelector('div.qz-tooltip');
                var info = parseDetails(td, tooltip);
                if (!info.teacher || !info.location || !info.sections) {
                    var itemInfo = parseDetails(items[0], null);
                    if (!info.teacher) info.teacher = itemInfo.teacher;
                    if (!info.location) info.location = itemInfo.location;
                    if (!info.sections) info.sections = itemInfo.sections;
                    if (!info.weekRange) info.weekRange = itemInfo.weekRange;
                }

                for (var it = 0; it < items.length; it++) {
                    var node = items[it];
                    var nameNode = node.querySelector ? node.querySelector('div.qz-hasCourse-title') : null;
                    var name = cleanText((nameNode || node).textContent);
                    if (!name) continue;

                    // 每条课程自己的节次最可靠：09~10小节 → 第九十节
                    var ownInfo = it === 0 ? null : parseDetails(node, null);
                    var secText = (ownInfo && ownInfo.sections) || info.sections;
                    var secNo = pickNum(secText);
                    var courseSlot = (secNo >= 1 && secNo <= 12) ? slotKeyOf(secNo) : rowSlot;
                    if (!courseSlot) courseSlot = rowSlot;

                    courses.push({
                        name: name,
                        teacher: (ownInfo && ownInfo.teacher) || info.teacher || '未知教师',
                        location: (ownInfo && ownInfo.location) || info.location || '未知地点',
                        weekRange: (ownInfo && ownInfo.weekRange) || info.weekRange,
                        dayOfWeek: dayInfo.day,
                        dayDate: dayInfo.date,
                        timeSlot: courseSlot,
                        sections: secText || courseSlot
                    });
                    rowCount++;
                }
            }
            if (rowCount) rowLog.push(rowSlot + '×' + rowCount);
            rowDiag.push(rowIdx + ':' + cells.length + (raw ? '≈' + raw.slice(0, 3) : '+'));
        });

        log('[JS] 行结构: ' + rowDiag.join(' '));
        log('[JS] 节次行: ' + slotRows.join('/'));
        if (rowLog.length) log('[JS] 各行课程数: ' + rowLog.join(', '));
        // 原始标题节点数 vs 解析数：两者不一致说明有课程被漏掉，需要看「行结构」定位
        var naive = table.querySelectorAll('div.qz-hasCourse-title').length;
        log('[JS] 课程标题: 原始 ' + naive + ' / 解析 ' + courses.length);
        return courses;
    }

    var lastList = [];   // 最近一次解析结果（延时复检时对比课程数）

    function parseTables(tables) {
        var all = [];
        var seen = {};
        for (var t = 0; t < tables.length; t++) {
            var list = parseTable(tables[t]);
            for (var c = 0; c < list.length; c++) {
                var co = list[c];
                var key = co.dayOfWeek + '|' + co.timeSlot + '|' + co.name + '|' + co.sections + '|' + co.weekRange;
                if (!seen[key]) {
                    seen[key] = 1;
                    all.push(co);
                }
            }
        }
        lastList = all;
        var preview = all.slice(0, 12).map(function (c) {
            return c.dayOfWeek + c.timeSlot + '/' + c.name + '/' + c.sections;
        });
        log('[JS] 课程样本: ' + preview.join(' ; '));
        log('[JS] 课表解析完成，共 ' + all.length + ' 门课程（表格 ' + tables.length + ' 个），正在回传');
        try {
            AndroidBack.onCourses(JSON.stringify(all));
        } catch (e) {
            err(e.message);
        }
    }

    function detectWeek(doc) {
        try {
            var re = /第\s*(\d{1,2})\s*周(?!\s*[-—~至])/;
            var i, m, t;
            var ss = doc.querySelectorAll('select');
            for (i = 0; i < ss.length; i++) {
                var so = ss[i].options[ss[i].selectedIndex];
                t = so ? (so.text || '') : '';
                m = t.match(re);
                if (m) return { n: parseInt(m[1], 10), src: 'select' };
            }
            var els = doc.querySelectorAll('button,span,div,a,li,td,th');
            for (i = 0; i < els.length; i++) {
                try {
                    var cl = String(els[i].className || '');
                    if (!/(cur|active|select|value|now|text|title)/i.test(cl)) continue;
                    t = cleanText(els[i].innerText).replace(/\s+/g, '');
                    if (t.length < 2 || t.length > 8) continue;
                    m = t.match(/^第(\d{1,2})周$/);
                    if (m) return { n: parseInt(m[1], 10), src: 'curcls' };
                } catch (_) {}
            }
            var bt = (doc.body && doc.body.innerText) || '';
            m = re.exec(bt);
            if (m) return { n: parseInt(m[1], 10), src: 'body' };
        } catch (_) {}
        return null;
    }

    function findTables() {
        var tables = [];
        function push(tb) {
            for (var k = 0; k < tables.length; k++) {
                if (tables[k] === tb) return;
            }
            tables.push(tb);
        }
        function collect(doc) {
            var tbs = doc.querySelectorAll('table');
            for (var a = 0; a < tbs.length; a++) {
                var tb = tbs[a];
                var cls = String(tb.className || '');
                if (cls.indexOf('qz-weeklyTable') >= 0 ||
                    tb.querySelector('ul.courselists') ||
                    tb.querySelector('td.qz-weeklyTable-td') ||
                    tb.querySelector('div.qz-hasCourse-title')) {
                    push(tb);
                }
            }
        }
        try { collect(document); } catch (_) {}

        // 依次扫所有 iframe（课表通常在内嵌的 person 框里）
        var frames = document.querySelectorAll('iframe,frame');
        for (var f = 0; f < frames.length; f++) {
            var doc = null;
            try {
                doc = frames[f].contentDocument;
            } catch (_) {}
            if (!doc || !doc.body) continue;
            try { frames[f].style.display = 'block'; } catch (_) {}
            try { collect(doc); } catch (_) {}
        }
        return tables;
    }

    /**
     * 延时复检：教务首页的周课表常由 ajax 异步渲染，首次解析可能只赶上部分课程。
     * 隔几秒再解析一次，若课程变多就重新回传。
     */
    function recheck(delay) {
        setTimeout(function () {
            try {
                var tables = findTables();
                if (!tables.length) return;
                QUIET = true;
                var n = parseTables(tables).length;
                QUIET = false;
                if (n > lastSent) {
                    lastSent = n;
                    log('[JS] 延时复检：课程增至 ' + n + ' 门，重新回传');
                    AndroidBack.onCourses(JSON.stringify(lastList));
                }
            } catch (_) { QUIET = false; }
        }, delay);
    }

    var lastSent = 0;

    var start = Date.now();
    function run() {
        var tables = findTables();
        if (tables.length) {
            log('[JS] 已找到课表表格 ' + tables.length + ' 个，开始解析');
            try {
                var docs = [document];
                var frames = document.querySelectorAll('iframe,frame');
                for (var f = 0; f < frames.length; f++) {
                    try {
                        if (frames[f].contentDocument && frames[f].contentDocument.body) {
                            docs.push(frames[f].contentDocument);
                        }
                    } catch (_) {}
                }
                for (var i = 0; i < docs.length; i++) {
                    var w = detectWeek(docs[i]);
                    if (w) {
                        try { AndroidBack.onWeekNo(String(w.n)); } catch (_) {}
                        log('[JS] 课表页周次检测: 第' + w.n + '周 (' + w.src + ')');
                        break;
                    }
                }
            } catch (_) {}
            parseTables(tables);
            lastSent = lastList.length;
            recheck(2500);
            recheck(7000);
            return;
        }
        if (Date.now() - start < 8000) {
            setTimeout(run, 400);
        } else {
            log('[JS] 8秒内未找到课表表格');
            err('未找到课表表格');
        }
    }

    try { run(); } catch (e) { log('[JS] 课表解析异常: ' + e.message); err(e.message); }
})();
