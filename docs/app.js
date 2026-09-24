/* Tileboard Platform docs — interactivity
   progress bar · scrollspy · mobile sidebar · copy buttons · mini syntax highlight · reveal */

(function () {
  "use strict";

  /* ---------- reading progress ---------- */
  var progressEl = document.querySelector(".progress");
  function updateProgress() {
    var doc = document.documentElement;
    var max = doc.scrollHeight - doc.clientHeight;
    var ratio = max > 0 ? doc.scrollTop / max : 0;
    if (progressEl) progressEl.style.width = (ratio * 100).toFixed(2) + "%";
  }
  window.addEventListener("scroll", updateProgress, { passive: true });
  updateProgress();

  /* ---------- mini syntax highlighter ----------
     Strategy: every generated <span> is immediately "stashed" and replaced by a
     placeholder made ONLY of non-word control chars (\u0000 \u0001 runs), so no
     later pass (keywords/numbers) can ever match inside already-generated markup. */
  var JAVA_KW = "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public record return sealed short static strictfp super switch synchronized this throw throws transient try var void volatile while yield true false null".split(" ");

  function escapeHtml(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function highlight(src, lang) {
    var escaped = escapeHtml(src);
    var store = [];
    function stash(html) {
      store.push(html);
      return "\u0000" + "\u0001".repeat(store.length) + "\u0000";
    }
    function span(cls, text) { return stash('<span class="' + cls + '">' + text + "</span>"); }

    // 1) comments (block, line, shell/yaml hash) — stashed first
    escaped = escaped.replace(/\/\*[\s\S]*?\*\//g, function (m) { return span("tok-com", m); });
    escaped = escaped.replace(/(^|[^:])\/\/[^\n]*/g, function (m, p1) {
      return p1 + span("tok-com", m.slice(p1.length));
    });
    if (lang === "bash" || lang === "yaml") {
      escaped = escaped.replace(/(^|\n)(\s*#[^\n]*)/g, function (m, nl, cm) {
        return nl + span("tok-com", cm);
      });
    }

    // 2) strings — stashed so keyword/number passes never touch them
    escaped = escaped.replace(/"(?:[^"\\\n]|\\.)*"/g, function (m) { return span("tok-str", m); });
    escaped = escaped.replace(/'(?:[^'\\\n]|\\.)*'/g, function (m) { return span("tok-str", m); });

    // 3) language-specific passes
    if (lang === "java") {
      escaped = escaped.replace(/@[A-Za-z_][\w.]*/g, function (m) { return span("tok-ann", m); });
      escaped = escaped.replace(/\b[A-Za-z_]\w*\b/g, function (w) {
        if (JAVA_KW.indexOf(w) >= 0) return span("tok-kw", w);
        if (/^[A-Z]/.test(w)) return span("tok-typ", w);
        return w;
      });
      escaped = escaped.replace(/\b(\d+(?:\.\d+)?[LlFfDd]?)\b/g, function (m) { return span("tok-num", m); });
    } else if (lang === "bash") {
      escaped = escaped.replace(/(^|\n)([ \t]*)((?:sudo |mvn |java |curl |git )+)/g, function (m, nl, sp, cmd) {
        return nl + sp + span("tok-kw", cmd.trimEnd());
      });
      escaped = escaped.replace(/(\s)(-{1,2}[\w-]+)/g, function (m, sp, flag) {
        return sp + span("tok-num", flag);
      });
    } else if (lang === "json") {
      escaped = escaped.replace(/\b(true|false|null)\b/g, function (m) { return span("tok-kw", m); });
      escaped = escaped.replace(/(-?\d+(?:\.\d+)?)/g, function (m) { return span("tok-num", m); });
    } else if (lang === "yaml") {
      escaped = escaped.replace(/^([ \t]*)([\w.-]+)(:)/gm, function (m, sp, key, colon) {
        return sp + span("tok-typ", key) + colon;
      });
    }

    // 4) restore stashed markup (unary index = length of the \u0001 run)
    escaped = escaped.replace(/\u0000(\u0001+)\u0000/g, function (_, run) {
      return store[run.length - 1];
    });
    return escaped;
  }

  /* ---------- decorate all code windows ---------- */
  document.querySelectorAll(".code-window").forEach(function (win) {
    var pre = win.querySelector("pre code");
    var lang = (win.getAttribute("data-lang") || "java").toLowerCase();
    if (pre && !pre.getAttribute("data-done")) {
      pre.innerHTML = highlight(pre.textContent.trimEnd(), lang);
      pre.setAttribute("data-done", "1");
    }
    var btn = win.querySelector(".copy-btn");
    if (btn) {
      btn.addEventListener("click", function () {
        var text = pre ? pre.textContent : "";
        function done() {
          btn.classList.add("done");
          btn.textContent = "\u2713 کپی شد";
          setTimeout(function () {
            btn.classList.remove("done");
            btn.textContent = "کپی";
          }, 1600);
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done, function () {});
        } else {
          var ta = document.createElement("textarea");
          ta.value = text; document.body.appendChild(ta);
          ta.select(); document.execCommand("copy"); ta.remove(); done();
        }
      });
    }
  });

  /* ---------- scrollspy for sidebar ---------- */
  var links = Array.prototype.slice.call(document.querySelectorAll(".sidebar a[href^='#']"));
  var sections = links
    .map(function (a) {
      var el = document.querySelector(a.getAttribute("href"));
      return el ? { link: a, el: el } : null;
    })
    .filter(Boolean);

  function spy() {
    var fromTop = window.scrollY + 140;
    var current = null;
    sections.forEach(function (s) {
      if (s.el.getBoundingClientRect().top + window.scrollY <= fromTop) current = s;
    });
    links.forEach(function (a) { a.classList.remove("active"); });
    if (current) current.link.classList.add("active");
  }
  window.addEventListener("scroll", spy, { passive: true });
  spy();

  /* ---------- mobile sidebar ---------- */
  var sidebar = document.querySelector(".sidebar");
  var menuBtn = document.querySelector(".menu-btn");
  var backdrop = document.querySelector(".sidebar-backdrop");
  function closeSidebar() {
    if (sidebar) sidebar.classList.remove("open");
    if (backdrop) backdrop.classList.remove("show");
  }
  if (menuBtn) menuBtn.addEventListener("click", function () {
    sidebar.classList.toggle("open");
    backdrop.classList.toggle("show", sidebar.classList.contains("open"));
  });
  if (backdrop) backdrop.addEventListener("click", closeSidebar);
  links.forEach(function (a) { a.addEventListener("click", closeSidebar); });

  /* ---------- reveal on scroll ---------- */
  if ("IntersectionObserver" in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) {
          e.target.classList.add("in");
          io.unobserve(e.target);
        }
      });
    }, { threshold: 0.06 });
    document.querySelectorAll(".reveal").forEach(function (el) { io.observe(el); });
  } else {
    document.querySelectorAll(".reveal").forEach(function (el) { el.classList.add("in"); });
  }
})();
