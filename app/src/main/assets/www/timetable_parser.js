/*
 * 课表解析脚本（由 HomeFragment.injectParser 注入到教务 WebView 执行）。
 * 放在 assets 里是为了避免 Java 字符串转义，便于维护与排查。
 * 关键点：
 *  1. 首页课表只渲染「当前教学周」，只在其他周上的课在 DOM 里根本不存在（第15周漏课的主因）；
 *     因此先回传当前周，再按 rq=all → 逐周 rq=1..N → 课表查询页 逐级补抓整学期课程并合并。
 *  2. 周次必须保留全部片段（如「[1-8周][10-16周]」不能只取第一段），否则后半学期的课会被过滤掉。
 *  3. 晚上 9-10 节按 sections 首节号优先归位，避免节次错行。
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
        // 旧版正方（/framework/mainV_index_loadkb.htmlx 返回的结构）：div.item-box
        var boxes = td.querySelectorAll('div.item-box');
        for (var b = 0; b < boxes.length; b++) items.push(boxes[b]);
        if (items.length) return items;
        // 课表查询页（/jsxsd/xskb/xskb_list.do）：div.kbcontent，课程名 + font[title] 系列
        var kbs = td.querySelectorAll('div.kbcontent');
        for (var kb = 0; kb < kbs.length; kb++) items.push(kbs[kb]);
        if (items.length) return items;
        // 少数模板直接把标题 div 挂在 td 下
        var titles = td.querySelectorAll('div.qz-hasCourse-title');
        for (var t = 0; t < titles.length; t++) items.push(titles[t]);
        return items;
    }

    /** 课程名：各模板结构不同，只取课程名本身，别把教师/地点/周次拼进名字 */
    function courseNameOf(node) {
        if (!node) return '';
        var title = node.querySelector ? node.querySelector('div.qz-hasCourse-title') : null;
        if (title) return cleanText(title.textContent);
        var p = node.querySelector ? node.querySelector('p') : null;
        if (p) return cleanText(p.textContent);
        // kbcontent：课程名是第一个 <br> 之前的那段裸文本
        var html = String(node.innerHTML || '');
        if (html.indexOf('<br') >= 0) {
            var first = html.split(/<br\s*\/?>/i)[0].replace(/<[^>]+>/g, ' ');
            var nm = cleanText(first);
            if (nm) return nm;
        }
        var txt = cleanText(node.textContent);
        return cleanText(
            txt.replace(/老师[:：]\s*\S+/g, '')
                .replace(/教师[:：]\s*\S+/g, '')
                .replace(/上课地点[:：]\s*\S+/g, '')
                .replace(/地点[:：]\s*\S+/g, '')
                .replace(/周次[:：].*$/, '')
                .replace(/节次[:：].*$/, '')
        );
    }

    function parseDetails(node, tooltip) {
        // groups：同一门课在不同周可能换教室（如第1周在 A101、第2-16周在 B202），
        // tooltip 会按「教师 / 上课地点 / 周次 / 节次」重复若干组，必须逐组拆开，否则整门课都显示第一周的教室。
        var info = { teacher: '', location: '', weekRange: '', sections: '', groups: [] };
        var groups = [];
        var infoWeeks = [];   // 扁平字段：所有组周次的并集（老逻辑兜底用）

        function newGroup() {
            var g = { teacher: '', location: '', weekParts: [], sections: '' };
            groups.push(g);
            return g;
        }
        function cur() {
            return groups.length ? groups[groups.length - 1] : newGroup();
        }
        function addWeek(t, g) {
            t = cleanText(t).replace(/^周次\s*[:：]/, '');
            if (!t || t.indexOf('周') < 0) return;
            if (g && g.weekParts.indexOf(t) < 0) g.weekParts.push(t);
            if (infoWeeks.indexOf(t) < 0) infoWeeks.push(t);
        }
        function read(divs) {
            for (var i = 0; i < divs.length; i++) {
                var text = cleanText(divs[i].textContent);
                if (text.indexOf('教师：') === 0 || text.indexOf('老师：') === 0) {
                    var tv = text.replace(/^(教师|老师)[:：]/, '');
                    if (!info.teacher) info.teacher = tv;
                    var g0 = cur();
                    if (!g0.teacher) g0.teacher = tv;
                } else if (text.indexOf('上课地点：') === 0 || text.indexOf('地点：') === 0) {
                    var lv = text.replace(/^(上课)?地点[:：]/, '');
                    // 已经有过地点说明这是新的一组（换了教室/周次）
                    var last = groups.length ? groups[groups.length - 1] : null;
                    var g = (last && last.location) ? newGroup() : cur();
                    if (!g.location) g.location = lv;
                    if (!info.location) info.location = lv;
                } else if (text.indexOf('节次：') === 0) {
                    var sv = text.replace(/^节次[:：]/, '');
                    if (!info.sections) info.sections = sv;
                    var gs = cur();
                    if (!gs.sections) gs.sections = sv;
                } else if (text.indexOf('周次：') === 0) {
                    addWeek(text, cur());
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
        // 旧版正方 item-box：div[not(@class)] 里 span[1]=地点，span[2]=周次(节次)
        try {
            var plain = node.querySelectorAll('div:not([class])');
            for (var pdi = 0; pdi < plain.length; pdi++) {
                var sps = plain[pdi].querySelectorAll('span');
                if (sps.length >= 1 && !info.location) info.location = cleanText(sps[0].textContent);
                if (sps.length >= 2) {
                    var ws = cleanText(sps[1].textContent);
                    addWeek(ws, cur());
                    if (!info.sections) {
                        var pm = ws.match(/[（(]([^）)]+)[）)]/);
                        if (pm) info.sections = pm[1];
                    }
                }
            }
            var tch = node.querySelector('div.tch-name');
            if (tch && !info.teacher) info.teacher = cleanText(tch.textContent);
            // 更老的正方课表：font[title=老师/上课地点/周次(节次)]
            var fonts = node.querySelectorAll('font[title]');
            for (var fi = 0; fi < fonts.length; fi++) {
                var ft = fonts[fi].getAttribute('title') || '';
                var fv = cleanText(fonts[fi].textContent);
                if ((ft.indexOf('老师') >= 0 || ft.indexOf('教师') >= 0) && !info.teacher) {
                    info.teacher = fv;
                } else if ((ft.indexOf('地点') >= 0 || ft.indexOf('教室') >= 0) && !info.location) {
                    info.location = fv;
                } else if (ft.indexOf('周次') >= 0 || ft.indexOf('节次') >= 0) {
                    addWeek(fv, cur());
                    if (!info.sections) {
                        var fm = fv.match(/[（(]([^）)]+)[）)]/);
                        if (fm) info.sections = fm[1];
                    }
                }
            }
        } catch (_) {}
        if (!info.location && own.length) {
            // 有的模板地点是纯文本，不带「上课地点：」前缀
            for (var k = 0; k < own.length; k++) {
                var tv2 = cleanText(own[k].textContent);
                if (tv2.indexOf('小节') >= 0 || tv2.charAt(0) === '[') continue;
                if (tv2.indexOf('教师') >= 0 || tv2.indexOf('周次') >= 0 || tv2.indexOf('节次') >= 0) continue;
                info.location = tv2;
            }
        }
        // 兜底：有的模板不给「周次：」前缀（如直接写「1-16周」「第15周」），
        // 从节点/悬浮框文本里按「a-b周」/「n周」提取，避免周次为空导致该课在所有周都显示
        if (!infoWeeks.length) {
            var scanNode = tooltip || node;
            var scan = cleanText((tooltip ? tooltip.textContent : '') + ' ' + (node ? node.textContent : ''));
            if (!scan && scanNode) scan = cleanText(scanNode.textContent);
            var re = /\d{1,2}\s*[-~—－至]\s*\d{1,2}\s*周(?:\s*[（(]?\s*[单双]\s*[）)]?)?|\d{1,2}\s*周(?:\s*[（(]?\s*[单双]\s*[）)]?)?/g;
            var m;
            while ((m = re.exec(scan)) !== null) addWeek(m[0], cur());
        }
        info.weekRange = infoWeeks.join(' ');

        // 只输出「有地点或周次」的组，避免空壳组把课程复制成多条
        var outGroups = [];
        for (var gi = 0; gi < groups.length; gi++) {
            var g2 = groups[gi];
            if (!g2.location && !g2.weekParts.length) continue;
            outGroups.push({
                teacher: g2.teacher || info.teacher,
                location: g2.location || '',
                weekRange: g2.weekParts.join(' '),
                sections: g2.sections || ''
            });
        }
        info.groups = outGroups;
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

        // ① 找「备注」列：部分模板在 7 天右侧多一列备注，若把它算进宽度，整张表会错位一天
        var notesCol = -1;
        for (var nr = 0; nr < allRows.length && nr < 6 && notesCol < 0; nr++) {
            var nCells = grid[nr] || [];
            for (var nc = 0; nc < nCells.length; nc++) {
                var nt = cleanText(nCells[nc].cell.textContent);
                if (nt.length <= 8 && nt.indexOf('备注') >= 0) { notesCol = nCells[nc].col; break; }
            }
        }

        // ② 课表正文的实际网格宽度：只统计「真的含课程」的行；
        //    之前把表头/备注等行也算进来，只要多出一列就会让 startCol 偏移，整张表课程错位一天
        var maxCols = 0;
        for (var mr = 0; mr < allRows.length; mr++) {
            var mCells = grid[mr] || [];
            if (mCells.length < 3) continue;
            var hasCourse = false;
            for (var mc = 0; mc < mCells.length && !hasCourse; mc++) {
                if (collectItems(mCells[mc].cell).length) hasCourse = true;
            }
            if (!hasCourse) continue;
            var lastCell = mCells[mCells.length - 1];
            var w = lastCell.col + lastCell.cs;
            if (notesCol >= 0 && w > notesCol) w = notesCol;   // 备注列不算星期列
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

                // 本格可能存在多个 tooltip（同格多门课）：按「课程名」匹配，避免所有课都用第一个 tooltip 的周次
                var tips = td.querySelectorAll('div.qz-tooltip');
                var tipAll = td.querySelector('div.qz-tooltip') || row.querySelector('div.qz-tooltip');
                var info = parseDetails(td, tipAll);
                var single = items.length === 1;
                var firstInfo = parseDetails(items[0], null);
                if (!info.teacher) info.teacher = firstInfo.teacher;
                if (!info.location) info.location = firstInfo.location;
                if (!info.sections) info.sections = firstInfo.sections;

                for (var it = 0; it < items.length; it++) {
                    var node = items[it];
                    var name = courseNameOf(node);
                    if (!name) continue;

                    var ownTip = node.querySelector ? node.querySelector('div.qz-tooltip') : null;
                    if (!ownTip && tips.length) {
                        for (var q = 0; q < tips.length; q++) {
                            if (cleanText(tips[q].textContent).indexOf(name) >= 0) { ownTip = tips[q]; break; }
                        }
                    }
                    if (!ownTip && single) ownTip = tipAll;   // 只有一门课时才能安全共用格子 tooltip
                    var ownInfo = parseDetails(node, ownTip);

                    // 旧版 item-box：一门课可能有多组「地点 + 周次(节次)」，逐组生成，避免节次/周次串位
                    var pats = [];
                    if (String(node.className || '').indexOf('item-box') >= 0 && node.querySelectorAll) {
                        var pdivs = node.querySelectorAll('div:not([class])');
                        for (var pi2 = 0; pi2 < pdivs.length; pi2++) {
                            var psps = pdivs[pi2].querySelectorAll('span');
                            if (psps.length < 2) continue;
                            var pws = cleanText(psps[1].textContent);
                            if (pws.indexOf('周') < 0) continue;
                            var psm = pws.match(/[（(]([^）)]+)[）)]/);
                            pats.push({
                                week: pws,
                                sec: psm ? psm[1] : '',
                                loc: cleanText(psps[0].textContent)
                            });
                        }
                    }
                    if (pats.length) {
                        for (var pp = 0; pp < pats.length; pp++) {
                            var pSec = pats[pp].sec;
                            var pNo = pickNum(pSec);
                            var pSlot = (pNo >= 1 && pNo <= 12) ? slotKeyOf(pNo) : rowSlot;
                            courses.push({
                                name: name,
                                teacher: ownInfo.teacher || info.teacher || '未知教师',
                                location: pats[pp].loc || ownInfo.location || '未知地点',
                                weekRange: pats[pp].week,
                                dayOfWeek: dayInfo.day,
                                dayDate: dayInfo.date,
                                timeSlot: pSlot || rowSlot,
                                sections: pSec || pSlot || rowSlot
                            });
                            rowCount++;
                        }
                        continue;
                    }

                    // 同一门课在不同周换教室/节次：tooltip 里是重复的「地点+周次」组，逐组生成，
                    // 这样选到第几周就显示那一周对应的教室，而不是永远显示第一周的教室。
                    var gs = (single && ownInfo.groups) ? ownInfo.groups : [];
                    var canSplit = gs.length > 1;
                    for (var chk = 0; chk < gs.length; chk++) {
                        if (!gs[chk].weekRange) { canSplit = false; break; }
                    }
                    if (canSplit) {
                        for (var gI = 0; gI < gs.length; gI++) {
                            var gg = gs[gI];
                            var gno = pickNum(gg.sections);
                            var gslot = (gno >= 1 && gno <= 12) ? slotKeyOf(gno) : rowSlot;
                            courses.push({
                                name: name,
                                teacher: gg.teacher || ownInfo.teacher || info.teacher || '未知教师',
                                location: gg.location || ownInfo.location || '未知地点',
                                weekRange: gg.weekRange,
                                dayOfWeek: dayInfo.day,
                                dayDate: dayInfo.date,
                                timeSlot: gslot || rowSlot,
                                sections: gg.sections || gslot || rowSlot
                            });
                            rowCount++;
                        }
                        continue;
                    }

                    // 多门课的格子绝不借用其他课的周次，宁可周次为空也不要张冠李戴（否则某些周会整门课消失）
                    var weekRange = ownInfo.weekRange || (single ? info.weekRange : '');
                    var secText = ownInfo.sections || (single ? info.sections : '');
                    var secNo = pickNum(secText);
                    var courseSlot = (secNo >= 1 && secNo <= 12) ? slotKeyOf(secNo) : rowSlot;
                    if (!courseSlot) courseSlot = rowSlot;

                    courses.push({
                        name: name,
                        teacher: ownInfo.teacher || info.teacher || '未知教师',
                        location: ownInfo.location || info.location || '未知地点',
                        weekRange: weekRange,
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

    var lastList = [];   // 最近一次回传的课程（延时复检/全学期补充时对比）
    var lastSent = 0;

    /** 周次规范化（只保留数字 + 单双标记），用于跨周页面去重 */
    function canonWeek(wr) {
        var s = String(wr || '');
        var parity = s.indexOf('单') >= 0 ? '单' : (s.indexOf('双') >= 0 ? '双' : '');
        // 去掉「(1-2节)」这类括号内容，否则节次数字会被当成周次参与去重
        var nums = (s.replace(/[（(][^）)]*[）)]/g, ' ').match(/\d{1,2}/g) || []);
        return nums.join(',') + parity;
    }

    function courseKey(co) {
        return co.dayOfWeek + '|' + co.timeSlot + '|'
            + String(co.name || '').replace(/\s+/g, '') + '|' + canonWeek(co.weekRange);
    }

    /** 解析多个表格并去重，返回课程数组（不回传） */
    function collectCourses(tables) {
        var all = [];
        var seen = {};
        for (var t = 0; t < tables.length; t++) {
            var list = parseTable(tables[t]);
            for (var c = 0; c < list.length; c++) {
                var co = list[c];
                var key = courseKey(co);
                if (!seen[key]) {
                    seen[key] = 1;
                    all.push(co);
                }
            }
        }
        return all;
    }

    /** 回传课程给原生层（前端 receiveFromAndroid('courses') 会覆盖并重渲染） */
    function deliver(list, tag) {
        lastList = list;
        lastSent = list.length;
        var preview = list.slice(0, 12).map(function (c) {
            return c.dayOfWeek + c.timeSlot + '/' + c.name + '/' + c.sections;
        });
        log('[JS] 课程样本: ' + preview.join(' ; '));
        log(tag || ('[JS] 课表解析完成，共 ' + list.length + ' 门课程，正在回传'));
        try {
            AndroidBack.onCourses(JSON.stringify(list));
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

    /** 在任意 document（含 DOMParser 解析出的文档）里挑出课表表格 */
    function pickTables(root) {
        var tables = [];
        function push(tb) {
            for (var k = 0; k < tables.length; k++) {
                if (tables[k] === tb) return;
            }
            tables.push(tb);
        }
        try {
            var tbs = root.querySelectorAll('table');
            for (var a = 0; a < tbs.length; a++) {
                var tb = tbs[a];
                var cls = String(tb.className || '');
                if (cls.indexOf('qz-weeklyTable') >= 0 ||
                    tb.querySelector('ul.courselists') ||
                    tb.querySelector('td.qz-weeklyTable-td') ||
                    tb.querySelector('div.qz-hasCourse-title') ||
                    tb.querySelector('div.kbcontent') ||
                    tb.querySelector('div.item-box')) {
                    push(tb);
                }
            }
        } catch (_) {}
        return tables;
    }

    function findTables() {
        var tables = [];
        function merge(list) {
            for (var i = 0; i < list.length; i++) {
                var tb = list[i];
                var dup = false;
                for (var k = 0; k < tables.length; k++) {
                    if (tables[k] === tb) { dup = true; break; }
                }
                if (!dup) tables.push(tb);
            }
        }
        try { merge(pickTables(document)); } catch (_) {}

        // 依次扫所有 iframe（课表通常在内嵌的 person 框里）
        var frames = document.querySelectorAll('iframe,frame');
        for (var f = 0; f < frames.length; f++) {
            var doc = null;
            try {
                doc = frames[f].contentDocument;
            } catch (_) {}
            if (!doc || !doc.body) continue;
            try { frames[f].style.display = 'block'; } catch (_) {}
            try { merge(pickTables(doc)); } catch (_) {}
        }
        return tables;
    }

    /** 读页面表单字段值：正方把 xnxqid / sjmsValue 放在 hidden input 里，innerHTML 里查不到 */
    function readFieldValues(doc) {
        var out = [];
        try {
            var els = doc.querySelectorAll('input,select,textarea');
            for (var i = 0; i < els.length; i++) {
                var el = els[i];
                var nm = String(el.name || el.id || '');
                if (!nm) continue;
                var v = '';
                if (String(el.tagName || '').toLowerCase() === 'select') {
                    var so = el.options[el.selectedIndex];
                    v = so ? (so.value || '') : '';
                } else {
                    v = el.value || el.getAttribute('value') || '';
                }
                if (v) out.push(nm + '=' + v);
            }
        } catch (_) {}
        return out.join(' ');
    }

    /**
     * 从主页面 / iframe / 脚本里找出课表接口所需参数（xnxqid 学期、sjmsValue 时间模式）：
     *   主页面课表只渲染「当前周」，因此只在其他周上课的课程在 DOM 里根本不存在。
     *   正方 jsxsd 用 mainV_index_loadkb.htmlx?rq=<周次> 切换周，rq=all 在部分学校直接给整学期。
     */
    function findSemesterParams() {
        var docs = [document];
        var frames = document.querySelectorAll('iframe,frame');
        for (var f = 0; f < frames.length; f++) {
            try {
                if (frames[f].contentDocument && frames[f].contentDocument.body) {
                    docs.push(frames[f].contentDocument);
                }
            } catch (_) {}
        }
        var reSj = /sjmsValue['"]?\s*[:=]\s*['"]?([A-Za-z0-9_-]{6,})/;
        var reXq = /xnxq(?:id|01id)['"]?\s*[:=]\s*['"]?([0-9]{4}-?[0-9]{4}-?[0-9]{1,2})/;
        var reXq2 = /xnxq(?:id|01id)=([^&'"\s]{2,40})/;
        var sj = '', xq = '';
        for (var i = 0; i < docs.length; i++) {
            var d = docs[i];
            var text = '';
            try {
                text = (d.documentElement ? d.documentElement.innerHTML : '') || '';
            } catch (_) {}
            try {
                // 页面地址本身也可能带参数（主框架跳转后参数会保留在 href 上）
                text += ' ' + (d.location ? String(d.location.href) : '');
            } catch (_) {}
            try {
                // 个别模板把参数放在本地存储里
                var st = d.defaultView;
                if (st && st.localStorage) {
                    for (var li = 0; li < st.localStorage.length; li++) {
                        var lk = st.localStorage.key(li);
                        text += ' ' + lk + '=' + (st.localStorage.getItem(lk) || '');
                    }
                }
            } catch (_) {}
            try {
                text += ' ' + String(document.cookie || '');
            } catch (_) {}
            try {
                text += ' ' + readFieldValues(d);
            } catch (_) {}
            if (!sj) {
                var m1 = text.match(reSj);
                if (m1) sj = m1[1];
            }
            if (!xq) {
                var m2 = text.match(reXq) || text.match(reXq2);
                if (m2) xq = m2[1];
            }
        }
        return { sj: sj, xq: xq };
    }

    /* ==================== 整学期课表补抓 ====================
     * 教务首页的周课表只渲染「当前教学周」，只在其他周上的课（例如只在第15周上的）
     * 在 DOM 里根本不存在，所以必须再向教务要一份整学期数据。按「省流量 → 最可靠」逐级降级：
     *   ① mainV_index_loadkb.htmlx?rq=all    一次请求拿整学期（部分学校支持）
     *   ② mainV_index_loadkb.htmlx?rq=1..N   首页周课表的切换接口，逐周抓再合并（最可靠）
     *   ③ xskb/xskb_list.do?zc=1..N          课表查询页（结构是 div.kbcontent），逐周抓
     * 逐周抓到的课若没写周次，就按「它出现在哪些周」反推区间（如 [1-16周]），不串周也不漏周。
     */
    var __supplementRunning = false;

    function fetchDoc(url, cb) {
        var settled = false;
        function done(html, ok) {
            if (settled) return;
            settled = true;
            cb(String(html || ''), !!ok);
        }
        try {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', url, true);
            try { xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest'); } catch (_) {}
            xhr.timeout = 4000;
            xhr.onreadystatechange = function () {
                if (xhr.readyState !== 4) return;
                done(xhr.responseText, xhr.status >= 200 && xhr.status < 400);
            };
            xhr.ontimeout = function () { done('', false); };
            xhr.onerror = function () { done('', false); };
            xhr.send();
        } catch (e) { done('', false); }
    }

    /** 解析一段课表 HTML；weekNo>0 表示这是「某一周」的课表，缺周次时按该周记 */
    function parseHtmlCourses(html, weekNo) {
        var doc = null;
        try { doc = new DOMParser().parseFromString(html, 'text/html'); } catch (_) {}
        if (!doc) return null;
        var tables = pickTables(doc);
        if (!tables.length) return null;
        QUIET = true;
        var list = [];
        try { list = collectCourses(tables); } catch (_) { list = []; } finally { QUIET = false; }
        if (weekNo > 0) {
            for (var i = 0; i < list.length; i++) {
                var co = list[i];
                if (co.weekRange) continue;          // tooltip 里写了真实周次，直接用
                co.weekRange = '第' + weekNo + '周';   // 没写：至少确定这门课在本周有课
                co.__inferred = true;
            }
        }
        return list;
    }

    /** 课程「基准键」（不含周次）：把逐周抓到的同一门课归并成一条 */
    function baseKeyOf(co) {
        return co.dayOfWeek + '|' + co.timeSlot + '|'
            + String(co.name || '').replace(/\s+/g, '') + '|'
            + String(co.teacher || '').replace(/\s+/g, '').replace(/^未知.*$/, '') + '|'
            + String(co.location || '').replace(/\s+/g, '').replace(/^未知.*$/, '');
    }

    function weekNoOfRange(wr) {
        var m = String(wr || '').match(/^第?\s*(\d{1,2})\s*周$/);
        return m ? parseInt(m[1], 10) : 0;
    }

    /** 周次集合 → 「[1-8周][10-16周]」，与教务写法一致，前端 parseWeekRanges 能直接解析 */
    function rangeTextOf(weeks) {
        weeks.sort(function (a, b) { return a - b; });
        var segs = [], i = 0;
        while (i < weeks.length) {
            var a = weeks[i], b = a;
            while (i + 1 < weeks.length && weeks[i + 1] === b + 1) { b = weeks[++i]; }
            segs.push(a === b ? (a + '周') : (a + '-' + b + '周'));
            i++;
        }
        return '[' + segs.join('][') + ']';
    }

    /**
     * 合并当前周课表 + 整学期课表：
     *  - 有真实周次的按「周次+课程」去重；
     *  - 只有「第N周」推断周次的按基准键归并，反推成区间；
     *  - 同一门课已有真实周次时丢弃推断条目，避免同一格出现两遍。
     */
    function buildMerged(domList, extraList) {
        var out = [], seen = {}, realBase = {};
        function addReal(list) {
            for (var i = 0; i < (list || []).length; i++) {
                var co = list[i];
                if (co.__inferred) continue;
                var k = courseKey(co);
                if (seen[k]) continue;
                seen[k] = 1;
                realBase[baseKeyOf(co)] = 1;
                out.push(co);
            }
        }
        addReal(domList);
        addReal(extraList);

        var groups = {}, order = [];
        for (var g = 0; g < (extraList || []).length; g++) {
            var ic = extraList[g];
            if (!ic.__inferred) continue;
            var bk = baseKeyOf(ic);
            if (realBase[bk]) continue;
            var wn = weekNoOfRange(ic.weekRange);
            if (wn <= 0) continue;
            if (!groups[bk]) { groups[bk] = { co: ic, weeks: [] }; order.push(bk); }
            if (groups[bk].weeks.indexOf(wn) < 0) groups[bk].weeks.push(wn);
        }
        for (var o = 0; o < order.length; o++) {
            var grp = groups[order[o]];
            var copy = {};
            for (var p in grp.co) { if (grp.co.hasOwnProperty(p)) copy[p] = grp.co[p]; }
            delete copy.__inferred;
            copy.weekRange = rangeTextOf(grp.weeks);
            var k2 = courseKey(copy);
            if (seen[k2]) continue;
            seen[k2] = 1;
            out.push(copy);
        }
        return out;
    }

    /** 学期周次范围：只处理第1~18周 */
    function estimateMaxWeek() {
        return 18;
    }

    function frameworkUrls(w, p) {
        var base = '/jsxsd/framework/mainV_index_loadkb.htmlx?rq=' + w;
        var xq = p.xq ? ('&xnxqid=' + encodeURIComponent(p.xq)) : '';
        var out = [];
        if (p.sj) out.push(base + '&sjmsValue=' + encodeURIComponent(p.sj) + xq + '&xswk=false');
        if (xq) out.push(base + xq + '&xswk=false');
        out.push(base);
        return out;
    }

    function xskbUrls(w, p) {
        var xq = p.xq ? ('&xnxq01id=' + encodeURIComponent(p.xq)) : '';
        var out = ['/jsxsd/xskb/xskb_list.do?zc=' + w + xq];
        if (xq) out.push('/jsxsd/xskb/xskb_list.do?zc=' + w + xq + '&xswk=false');
        return out;
    }

    /**
     * 逐周抓取 [a,b] 的课表并汇总。
     * urlsOf(w,p) 给出该周的若干候选地址；某种地址一旦成功，后续周优先用它，避免重复试错。
     */
    function fetchWeekRange(a, b, p, urlsOf, cb) {
        var results = [], nextW = a, active = 0, finished = false, winShape = -1;
        var CONC = 3;

        function shapeList(w) {
            var arr = urlsOf(w, p);
            var idxs = [];
            for (var i = 0; i < arr.length; i++) idxs.push(i);
            if (winShape >= 0) {
                idxs.sort(function (x, y) { return x === winShape ? -1 : (y === winShape ? 1 : 0); });
            }
            var out = [];
            for (var k = 0; k < idxs.length; k++) out.push({ i: idxs[k], u: arr[idxs[k]] });
            return out;
        }

        function pump() {
            while (active < CONC && nextW <= b) fetchWeek(nextW++);
            if (!finished && active === 0 && nextW > b) {
                finished = true;
                cb(results);
            }
        }

        function fetchWeek(w) {
            active++;
            var shapes = shapeList(w);
            var done = false;
            function step(k) {
                if (done) return;
                if (k >= shapes.length) { done = true; active--; pump(); return; }
                fetchDoc(shapes[k].u, function (html) {
                    if (done) return;
                    var list = parseHtmlCourses(html, w);
                    if (list && list.length) {
                        done = true;
                        winShape = shapes[k].i;
                        results.push({ w: w, list: list });
                        active--;
                        pump();
                        return;
                    }
                    step(k + 1);
                });
            }
            step(0);
        }
        pump();
    }

    function weekCountLog(results) {
        var cnt = [];
        for (var i = 0; i < results.length; i++) cnt.push(results[i].w + ':' + results[i].list.length);
        return cnt.join(' ');
    }

    function mergeAndDeliver(extraList, tagLog) {
        var merged = buildMerged(lastList, extraList);
        if (merged.length <= lastList.length) return false;
        log(tagLog + '：新增 ' + (merged.length - lastList.length) + ' 门，合并后共 ' + merged.length + ' 门');
        deliver(merged, '[JS] 整学期课表合并完成，共 ' + merged.length + ' 门课程，正在回传');
        return true;
    }

    /** 当前周数据已回传后，补齐「只在其他周上课」的课程（第15周这类漏课就在这里补回来） */
    function supplementAllWeeks() {
        if (__supplementRunning) return;
        __supplementRunning = true;
        var p = findSemesterParams();
        log('[JS] 整学期补抓开始：xnxqid=' + (p.xq || '缺失') + '，sjmsValue=' + (p.sj ? '已取到' : '缺失'));

        // ① rq=all：一次请求拿整学期，最省流量
        var allUrls = frameworkUrls('all', p);
        function tryAll(i) {
            if (i >= allUrls.length) { log('[JS] rq=all 没返回课表，改用逐周抓取'); weeks(); return; }
            fetchDoc(allUrls[i], function (html) {
                var list = parseHtmlCourses(html, 0);
                if (list && list.length) {
                    log('[JS] rq=all 返回 ' + list.length + ' 门课程');
                    if (mergeAndDeliver(list, 'rq=all')) return;
                    log('[JS] rq=all 的课程与当前周完全一致（参数可能被忽略），改用逐周抓取');
                }
                tryAll(i + 1);
            });
        }

        // ② 逐周抓首页课表接口（rq=1..N）
        function weeks() {
            var maxW = estimateMaxWeek();
            log('[JS] 逐周抓取第1~' + maxW + '周（首页课表接口）');
            fetchWeekRange(1, maxW, p, frameworkUrls, function (results) {
                var loopList = [];
                for (var i = 0; i < results.length; i++) loopList = loopList.concat(results[i].list);
                log('[JS] 逐周结果（周:条数）: ' + weekCountLog(results));
                if (mergeAndDeliver(loopList, '逐周抓取')) return;
                log('[JS] 逐周抓取没得到新课程，改试课表查询页');
                xskb();
            });
        }

        // ③ 课表查询页（结构不同：div.kbcontent）
        function xskb() {
            var maxW = estimateMaxWeek();
            log('[JS] 逐周抓取第1~' + maxW + '周（课表查询页）');
            fetchWeekRange(1, maxW, p, xskbUrls, function (results) {
                var loopList = [];
                for (var i = 0; i < results.length; i++) loopList = loopList.concat(results[i].list);
                log('[JS] 课表查询页逐周结果（周:条数）: ' + weekCountLog(results));
                if (mergeAndDeliver(loopList, '课表查询页逐周抓取')) return;
                log('[JS] 三种接口都没拿到更多课程，课表仍以当前周数据为准');
            });
        }

        tryAll(0);
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
                var list = collectCourses(tables);
                QUIET = false;
                if (list.length > lastSent) {
                    log('[JS] 延时复检：课程增至 ' + list.length + ' 门，重新回传');
                    deliver(list, '[JS] 延时复检回传，共 ' + list.length + ' 门课程');
                }
            } catch (_) { QUIET = false; }
        }, delay);
    }

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
            // 先回传当前周课表（保证 App 立即可用）
            var list = collectCourses(tables);
            deliver(list, '[JS] 当前周课表解析完成，共 ' + list.length + ' 门课程（表格 ' + tables.length + ' 个），正在回传');
            recheck(2500);
            recheck(7000);
            // 再拉整学期课表，把只在其他周上课的课程补进来（否则第15周这类课会查不到）
            setTimeout(supplementAllWeeks, 800);
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
