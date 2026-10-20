/* 滚动时间选择器：小时 + 分钟双列滚轮，兼容旧版 WebView（不依赖 CSS scroll-snap） */
(function () {
    'use strict';
    var ITEM_H = 44;
    var PAD = 88; // 首页/末页留白，使首尾项也能居中

    var built = false;
    var mask, hourCol, minCol, titleEl;
    var onOk = null;
    var hours = [], mins = [];
    for (var h = 0; h < 24; h++) hours.push((h < 10 ? '0' : '') + h);
    for (var m = 0; m < 60; m++) mins.push((m < 10 ? '0' : '') + m);

    function build() {
        if (built) return;
        built = true;
        var wrap = document.createElement('div');
        wrap.className = 'wheel-mask hidden';
        wrap.id = 'wheelPickerMask';
        wrap.innerHTML =
            '<div class="wheel-sheet">' +
            '<div class="wheel-head">' +
            '<button type="button" class="wheel-cancel">取消</button>' +
            '<h3 id="wheelTitle">选择时间</h3>' +
            '<button type="button" class="wheel-ok">确定</button>' +
            '</div>' +
            '<div class="wheel-body"><div class="wheel-hi"></div>' +
            '<div class="wheel-col" id="wheelHourCol"></div>' +
            '<span class="wheel-sep">:</span>' +
            '<div class="wheel-col" id="wheelMinCol"></div>' +
            '</div></div>';
        document.body.appendChild(wrap);

        mask = wrap;
        titleEl = wrap.querySelector('#wheelTitle');
        hourCol = wrap.querySelector('#wheelHourCol');
        minCol = wrap.querySelector('#wheelMinCol');
        buildCol(hourCol, hours);
        buildCol(minCol, mins);

        wrap.querySelector('.wheel-cancel').addEventListener('click', close, false);
        wrap.querySelector('.wheel-ok').addEventListener('click', confirm, false);
        wrap.addEventListener('click', function (e) {
            if (e.target === wrap) close();
        }, false);
    }

    function buildCol(col, vals) {
        var html = '';
        for (var i = 0; i < vals.length; i++) {
            html += '<div class="wheel-item" data-i="' + i + '">' + vals[i] + '</div>';
        }
        col.innerHTML = html;
        var timer = null;
        col.addEventListener('scroll', function () {
            highlight(col);
            if (timer) clearTimeout(timer);
            timer = setTimeout(function () { snap(col); }, 130);
        }, false);
        col.addEventListener('touchend', function () {
            if (timer) clearTimeout(timer);
            timer = setTimeout(function () { snap(col); }, 130);
        }, false);
    }

    function indexOfCol(col) {
        var n = Math.round(col.scrollTop / ITEM_H);
        if (n < 0) n = 0;
        if (n > col.children.length - 1) n = col.children.length - 1;
        return n;
    }

    function highlight(col) {
        var idx = indexOfCol(col);
        var items = col.children;
        for (var i = 0; i < items.length; i++) {
            items[i].className = (i === idx) ? 'wheel-item active' : 'wheel-item';
        }
    }

    function snap(col) {
        var idx = indexOfCol(col);
        var top = idx * ITEM_H;
        if (Math.abs(col.scrollTop - top) > 1) {
            try {
                col.scrollTo({ top: top, behavior: 'smooth' });
            } catch (e) {
                col.scrollTop = top;
            }
        }
        highlight(col);
    }

    function setCol(col, val) {
        var idx = 0;
        for (var i = 0; i < col.children.length; i++) {
            if (col.children[i].textContent === val) { idx = i; break; }
        }
        col.scrollTop = idx * ITEM_H;
        highlight(col);
    }

    function open(opts) {
        build();
        opts = opts || {};
        titleEl.textContent = opts.title || '选择时间';
        mask.classList.remove('hidden');
        var parts = String(opts.value || '08:00').split(':');
        setCol(hourCol, parts[0] || '08');
        setCol(minCol, parts[1] || '00');
        onOk = opts.onOk || null;
        // 显示后再次校正（隐藏状态下设置 scrollTop 在部分 WebView 不生效）
        setTimeout(function () {
            var p2 = String(opts.value || '08:00').split(':');
            setCol(hourCol, p2[0] || '08');
            setCol(minCol, p2[1] || '00');
        }, 30);
    }

    function close() {
        if (mask) mask.classList.add('hidden');
        onOk = null;
    }

    function confirm() {
        var hh = hours[indexOfCol(hourCol)];
        var mm = mins[indexOfCol(minCol)];
        var cb = onOk;
        close();
        if (cb) cb(hh + ':' + mm);
    }

    window.WheelPicker = {
        open: open,
        close: close
    };
})();
