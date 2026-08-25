/* NFC in Time — landing runtime.
   Pulls the aggregate board, keeps the world counter honest between polls,
   renders the top 20, switches language, reveals blocks on scroll. */

(function () {
  'use strict';

  var YEAR = 31557600;             // 365.25 d, same constant the client uses
  var DAY  = 86400;
  var BOARD_URL = '/v1/board';
  var TOP_URL   = '/v1/top';
  var POLL_MS   = 60000;

  var DICT = {
    en: {
      world_time: 'Time in the world',
      cap_years: 'Years', cap_days: 'Days', cap_hours: 'Hours',
      cap_min: 'Min', cap_sec: 'Sec',
      s_alive: 'Alive', s_dead: 'Zeroed', s_deals: 'Deals',
      s_transferred: 'Transferred in a day',
      top: 'Top 20',
      t_rank: '#', t_player: 'Player', t_time: 'Time', t_diff: 'Δ 24h',
      t_empty: 'Nobody is playing yet',
      mechanic: 'Time changes hands',
      r1: 'The hand on top takes, the hand below gives — and the roles swap '
        + 'the moment you turn the devices over',
      r2: 'Hold the devices in balance and time does not move in either direction',
      r3: 'Holding on is both the payoff and the risk: by the thirtieth second '
        + 'the stake is years per second',
      mm: 'mm',
      get: 'Your time is running', apk: 'Download APK', by: 'Made by',
      y: ['year', 'years'], d: ['day', 'days'],
      h: ['hour', 'hours'], m: ['minute', 'minutes'],
      u_y: 'y', u_d: 'd', u_h: 'h', u_m: 'm'
    },
    ru: {
      world_time: 'Времени в мире',
      cap_years: 'Лет', cap_days: 'Дней',
      cap_hours: 'Часов', cap_min: 'Мин',
      cap_sec: 'Сек',
      s_alive: 'Живых',
      s_dead: 'Обнулены',
      s_deals: 'Сделок',
      s_transferred: 'Передано за сутки',
      top: 'Топ 20',
      t_rank: '#', t_player: 'Игрок',
      t_time: 'Время', t_diff: 'Δ 24ч',
      t_empty: 'Пока никто не играет',
      mechanic: 'Время меняет руки',
      r1: 'Рука сверху забирает, рука снизу отдаёт — и роли меняются в ту же '
        + 'секунду, как вы развернёте устройства',
      r2: 'Держите равновесие между устройствами — и время не двигается '
        + 'ни в одну сторону',
      r3: 'Держать дольше и выгоднее, и опаснее: к тридцатой секунде ставка '
        + 'в игре — годы за секунду',
      mm: 'мм',
      get: 'Время пошло',
      apk: 'Скачать APK',
      by: 'Сделал',
      y: ['год', 'года', 'лет'],
      d: ['день', 'дня', 'дней'],
      h: ['час', 'часа', 'часов'],
      m: ['минута', 'минуты', 'минут'],
      u_y: 'г.', u_d: 'д.', u_h: 'ч.', u_m: 'м.'
    }
  };

  var lang = pickLang();

  function pickLang() {
    var saved;
    try { saved = localStorage.getItem('nfcit.lang'); } catch (e) {}
    if (DICT[saved]) return saved;
    return /^ru\b/i.test(navigator.language || '') ? 'ru' : 'en';
  }

  function t(key) { return DICT[lang][key]; }

  function applyLang() {
    document.documentElement.lang = lang;
    document.querySelectorAll('[data-i18n]').forEach(function (el) {
      el.textContent = t(el.dataset.i18n);
    });
    document.querySelectorAll('.lang__opt').forEach(function (btn) {
      btn.setAttribute('aria-current', String(btn.dataset.lang === lang));
    });
    renderStats();
    renderTop();
    paintGap();
  }

  document.querySelectorAll('.lang__opt').forEach(function (btn) {
    btn.addEventListener('click', function () {
      lang = btn.dataset.lang;
      try { localStorage.setItem('nfcit.lang', lang); } catch (e) {}
      applyLang();
    });
  });

  /* Neither the counter nor the table is drawn before the server answers.
     The markup ships with dashes in every field, and filling them with an
     invented board would be a lie rather than a fallback. */
  var board = null;
  var top20 = null;

  var slots = {};
  document.querySelectorAll('[data-slot]').forEach(function (el) {
    slots[el.dataset.slot] = el;
  });
  var stats = {};
  document.querySelectorAll('[data-stat]').forEach(function (el) {
    stats[el.dataset.stat] = el;
  });
  var topBody = document.getElementById('top-body');
  var boardEl = document.querySelector('.board');

  /* The board's scale is capped by the height it needs, and that depends on how
     many rows it has. Everything around the table costs about 24 units, a row
     3.35 — 5.3 once a short board switches to its roomier rows. */
  var SPARSE = 8;

  function fitBoard(rows) {
    if (!boardEl) return;
    var sparse = rows <= SPARSE;
    boardEl.classList.toggle('is-sparse', sparse);
    boardEl.style.setProperty(
      '--fit', (1 / (24 + (sparse ? 5.3 : 3.35) * rows)).toFixed(5));
  }

  function group(n) {
    return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }

  function pad(n) { return n < 10 ? '0' + n : String(n); }

  function breakdown(total) {
    var s = Math.max(0, Math.floor(total));
    var years = Math.floor(s / YEAR);  s -= years * YEAR;
    var days  = Math.floor(s / DAY);   s -= days * DAY;
    var hours = Math.floor(s / 3600);  s -= hours * 3600;
    var mins  = Math.floor(s / 60);    s -= mins * 60;
    return { years: years, days: days, hours: hours, minutes: mins, seconds: s };
  }

  /* English has two number forms, Russian three — pick by the usual rules. */
  function plural(n, key) {
    var forms = DICT[lang][key];
    if (forms.length === 2) return forms[n === 1 ? 0 : 1];

    var mod10 = n % 10, mod100 = n % 100;
    if (mod10 === 1 && mod100 !== 11) return forms[0];
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return forms[1];
    return forms[2];
  }

  /* Phones get "2 y 364 d" instead of "2 years 364 days": at this width the
     spelled-out unit is wider than the number it belongs to. */
  var narrow = window.matchMedia
    ? window.matchMedia('(max-width: 620px)')
    : { matches: false };

  function unit(n, key) {
    return group(n) + ' ' + (narrow.matches ? t('u_' + key) : plural(n, key));
  }

  /* Coarse span, spelled out: "73 years 119 days", "119 дней 4 часа". */
  function span(total) {
    var b = breakdown(Math.abs(total));
    if (b.years) return unit(b.years, 'y') + ' ' + unit(b.days, 'd');
    if (b.days)  return unit(b.days, 'd')  + ' ' + unit(b.hours, 'h');
    if (b.hours) return unit(b.hours, 'h') + ' ' + unit(b.minutes, 'm');
    return unit(b.minutes, 'm');
  }

  function signed(total) {
    return (total < 0 ? '−' : '+') + span(total);
  }

  function now() {
    return (typeof performance !== 'undefined' && performance.now)
      ? performance.now() / 1000
      : Date.now() / 1000;
  }

  /* Not a clock — the sum of every living player's balance. Everyone starts
     at 25 years and burns a second per second just by being alive, so the
     pool drains at `alive` seconds per second and only ever goes down;
     transfers move time between players without changing the total.

     Derived from a fixed origin rather than accumulated per tick, so a
     throttled background tab doesn't fall behind. */
  var origin = null;

  function render() {
    if (!origin) return;

    var elapsed = now() - origin.at;
    var b = breakdown(origin.seconds - elapsed * board.alive);

    slots.years.textContent   = group(b.years);
    slots.days.textContent    = b.days;
    slots.hours.textContent   = pad(b.hours);
    slots.minutes.textContent = pad(b.minutes);
    slots.seconds.textContent = pad(b.seconds);
  }

  function renderStats() {
    if (!board) return;

    stats.alive.textContent       = group(board.alive);
    stats.dead.textContent        = group(board.dead);
    stats.deals.textContent       = group(board.deals);
    stats.transferred.textContent = span(board.transferred_24h);
  }

  function renderTop() {
    if (!top20) return;

    fitBoard(top20.length || 1);

    if (!top20.length) {
      topBody.innerHTML = '<tr><td class="top__empty" colspan="4">'
                        + esc(t('t_empty')) + '</td></tr>';
      return;
    }

    var html = '';
    for (var i = 0; i < top20.length; i++) {
      var r = top20[i];
      html += '<tr>'
            + '<td class="top__rank">' + pad(i + 1) + '</td>'
            + '<td class="top__who">' + esc(r.name) + '</td>'
            + '<td class="top__time">' + span(r.seconds) + '</td>'
            + '<td class="top__diff' + (r.diff_24h > 0 ? ' is-up' : '') + '">'
            + signed(r.diff_24h) + '</td>'
            + '</tr>';
    }
    topBody.innerHTML = html;
  }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function adopt(data) {
    if (!data || typeof data.world_seconds !== 'number') return;

    board = {
      world_seconds:   data.world_seconds,
      alive:           data.alive           || 0,
      dead:            data.dead            || 0,
      deals:           data.deals           || 0,
      transferred_24h: data.transferred_24h || 0
    };

    /* `as_of` is the server's unix second the snapshot was taken at. A cached
       response arrives stale, so drain the pool forward before adopting it. */
    var stale = 0;
    if (typeof data.as_of === 'number') {
      stale = Math.max(0, Date.now() / 1000 - data.as_of);
    }

    origin = { seconds: board.world_seconds - stale * board.alive, at: now() };
    renderStats();
  }

  function adoptTop(data) {
    /* null means the request failed; an empty array means the board really is
       empty, and the table has to say so. */
    if (!data) return;
    top20 = data.slice(0, 20).map(function (r) {
      return {
        name: r.name || '—',
        seconds: r.seconds || 0,
        diff_24h: r.diff_24h || 0
      };
    });
    renderTop();
  }

  function get(url, ok) {
    fetch(url, { headers: { accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(ok)
      .catch(function () { /* offline or no backend yet — keep ticking */ });
  }

  function poll() {
    get(BOARD_URL, adopt);
    get(TOP_URL, adoptTop);
  }

  function onNarrowChange() {
    renderStats();
    renderTop();
  }

  if (narrow.addEventListener) narrow.addEventListener('change', onNarrowChange);
  else if (narrow.addListener) narrow.addListener(onNarrowChange);

  function reveal() {
    var items = [].slice.call(document.querySelectorAll('[data-reveal]'));

    if (!('IntersectionObserver' in window)) {
      items.forEach(function (el) { el.classList.add('is-in'); });
      return;
    }

    var io = new IntersectionObserver(function (entries) {
      /* Stagger whatever came into view together, in document order. */
      var shown = entries.filter(function (e) { return e.isIntersecting; })
        .map(function (e) { return e.target; })
        .sort(function (a, b) {
          return (a.compareDocumentPosition(b) & 4) ? -1 : 1;
        });

      shown.forEach(function (el, i) {
        el.style.setProperty('--d', (i * 0.24) + 's');
        el.classList.add('is-in');
        io.unobserve(el);

        /* Drop the animation — and with it the compositing layer — the
           moment the block has arrived, so no seam is left behind. */
        el.addEventListener('transitionend', function done(e) {
          if (e.propertyName !== 'opacity') return;
          el.removeEventListener('transitionend', done);
          el.style.removeProperty('--d');
          el.classList.add('is-done');
        });
      });
    }, { rootMargin: '0px 0px -12% 0px', threshold: 0.02 });

    items.forEach(function (el) { io.observe(el); });
  }

  /* The two phones drift apart and back together, and the dimension reads
     whatever the drawing actually shows. One rAF loop drives both, so the
     number can never disagree with the picture. */
  var BASE_MM = 127;
  var AMP_MM  = 9;
  var PERIOD  = 7000;

  var gapMm = BASE_MM;
  var gapValue = document.getElementById('gap-value');

  function paintGap() {
    if (gapValue) gapValue.textContent = Math.round(gapMm) + ' ' + t('mm');
  }

  function breathe() {
    var fig = document.querySelector('.diagram');
    if (!fig) return;

    var phones = fig.querySelectorAll('.phone');
    var gapEl  = fig.querySelector('.gap');
    var rule   = fig.querySelector('.gap__rule');
    if (phones.length < 2 || !gapEl) return;

    if (window.matchMedia &&
        window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

    var gapPx = gapEl.offsetHeight;
    var visible = true;
    var running = false;
    var t0 = 0;

    window.addEventListener('resize', function () { gapPx = gapEl.offsetHeight; });

    function frame(ts) {
      if (!running) return;
      if (!t0) t0 = ts;

      gapMm = BASE_MM + AMP_MM * Math.sin((ts - t0) / PERIOD * 2 * Math.PI);

      /* millimetres converted at the drawing's own scale */
      var px = (gapMm - BASE_MM) * (gapPx / BASE_MM);

      phones[0].style.transform = 'translateY(' + (-px / 2).toFixed(2) + 'px)';
      phones[1].style.transform = 'translateY(' + ( px / 2).toFixed(2) + 'px)';
      if (rule) rule.style.transform = 'scaleY(' + (1 + px / gapPx).toFixed(4) + ')';
      paintGap();

      requestAnimationFrame(frame);
    }

    function sync() {
      var want = visible && document.visibilityState === 'visible';
      if (want === running) return;
      running = want;
      if (running) { t0 = 0; requestAnimationFrame(frame); }
    }

    /* idle whenever the drawing is off screen or the tab is in the background */
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (e) {
        visible = e[0].isIntersecting;
        sync();
      }, { threshold: 0.05 }).observe(fig);
    }
    document.addEventListener('visibilitychange', sync);
    sync();
  }

  /* The splash plays once, then stays gone until the visitor has been away
     for more than ten minutes. `nfcit.seen` is kept warm while the tab is
     open, so the gap counts from the last time the page was actually used —
     not from when the splash happened to run. */
  var INTRO_MS = 3000;

  function touch() {
    try { localStorage.setItem('nfcit.seen', String(Date.now())); } catch (e) {}
  }

  function intro(done) {
    var el = document.getElementById('intro');
    var root = document.documentElement;

    if (!el) { done(); return; }
    if (!root.classList.contains('intro-on')) { el.remove(); touch(); done(); return; }

    var reduced = window.matchMedia &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var duration = reduced ? 1600 : INTRO_MS;

    el.style.setProperty('--intro-dur', (duration / 1000) + 's');
    touch();

    var timer = setTimeout(close, duration);

    function close() {
      clearTimeout(timer);
      window.removeEventListener('keydown', onKey);
      el.removeEventListener('click', close);

      el.classList.add('is-out');
      root.classList.remove('intro-on');
      setTimeout(function () { el.remove(); }, 800);
      done();
    }

    function onKey(e) {
      if (e.key === 'Escape' || e.key === 'Enter' || e.key === ' ') close();
    }

    window.addEventListener('keydown', onKey);
    el.addEventListener('click', close);
  }

  /* keep the visit warm */
  setInterval(touch, 60000);
  window.addEventListener('pagehide', touch);
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') touch();
  });

  applyLang();
  render();
  setInterval(render, 250);
  poll();
  setInterval(poll, POLL_MS);

  /* blocks start arriving only once the splash is out of the way */
  intro(function () {
    requestAnimationFrame(reveal);
    breathe();
  });
})();
