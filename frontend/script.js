"use strict";

/* ============================ constants ============================ */

const MOODS = [
  { label: "Happy",   glyph: "\u2600", color: "#B39A63" },
  { label: "Calm",    glyph: "\u2601", color: "#87927A" },
  { label: "Sad",     glyph: "\u263E", color: "#6E7A8A" },
  { label: "Excited", glyph: "\u2726", color: "#713F3F" },
  { label: "Loved",   glyph: "\u2661", color: "#B98F88" },
  { label: "Neutral", glyph: "\u25CB", color: "#8A7A68" }
];

const PROMPTS = [
  { tag: "Tonight",     q: "What is something you want to remember about today?" },
  { tag: "This week",   q: "Who made your week lighter, and did you tell them?" },
  { tag: "Slowly",      q: "What are you doing out of habit rather than love?" },
  { tag: "Honestly",    q: "What did you avoid, and what was underneath it?" },
  { tag: "Small things",q: "Name three ordinary things you would miss." },
  { tag: "Ahead",       q: "What would make tomorrow feel like enough?" }
];

const MONTHS = ["January","February","March","April","May","June","July","August","September","October","November","December"];
const MONTHS_ABBR = ["JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"];
const WEEKDAYS = ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"];
const WEEKDAYS_FULL = ["Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"];

/* ============================ state ============================ */

const state = {
  screen: "home",
  turning: false,
  entries: [],
  stats: null,
  mood: null,
  reading: null,
  opening: false,
  editing: null,
  calYear: null,
  calMonth: null,
  calBusy: false
};

/* ============================ helpers ============================ */

const $ = (sel) => document.querySelector(sel);

function esc(str) {
  return String(str == null ? "" : str)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

function moodOf(label) {
  return MOODS.find(m => m.label.toLowerCase() === String(label || "").toLowerCase()) || MOODS[5];
}

function parseIso(iso) {
  if (!iso) return null;
  const p = String(iso).split("-");
  if (p.length !== 3) return null;
  const d = new Date(Number(p[0]), Number(p[1]) - 1, Number(p[2]));
  return isNaN(d.getTime()) ? null : d;
}

function fmtFull(iso) {
  const d = parseIso(iso);
  return d ? `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}` : iso;
}

function fmtShort(iso) {
  const d = parseIso(iso);
  return d ? `${d.getDate()} ${MONTHS_ABBR[d.getMonth()]}` : iso;
}

function todayIso() {
  const d = new Date();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${m}-${day}`;
}

/** Visible text from the contenteditable page, including blank lines from Enter. */
function readJournalBody() {
  const el = $("#writeBody");
  if (!el) return "";
  const raw = el.innerText != null ? el.innerText : (el.textContent || "");
  return raw.replace(/\r\n/g, "\n").replace(/\u00a0/g, " ");
}

function writeJournalBody(text) {
  const el = $("#writeBody");
  if (!el) return;
  el.innerText = text || "";
}

function oneLine(text) {
  return String(text || "").replace(/\s+/g, " ").trim();
}

/* ============================ API ============================ */

async function apiGet(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error("GET failed " + res.status);
  return res.json();
}

async function apiSend(url, method, body) {
  const res = await fetch(url, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Request failed");
  return data;
}

async function loadData() {
  try {
    const [entries, stats] = await Promise.all([
      apiGet("/api/entries"),
      apiGet("/api/stats")
    ]);
    state.entries = entries;
    state.stats = stats;
    renderAll();
  } catch (e) {
    console.error(e);
  }
}

/* ============================ navigation / screen turn ============================ */

function go(screen) {
  if (screen === state.screen && !state.opening) { closeSearch(); return; }
  state.turning = true;
  const main = $("#main");
  main.classList.add("turning");
  closeSearch();

  setTimeout(() => {
    document.querySelectorAll(".screen").forEach(s => s.classList.remove("active"));
    const target = $(`#screen-${screen}`);
    if (target) target.classList.add("active");
    state.screen = screen;
    state.turning = false;
    main.classList.remove("turning");
    window.scrollTo(0, 0);
    setNavActive(screen);

    const wrap = $("#writeWrap");
    if (screen === "write") {
      wrap.classList.remove("paper-in");
      requestAnimationFrame(() => requestAnimationFrame(() => wrap.classList.add("paper-in")));
    } else {
      wrap.classList.remove("paper-in");
    }
  }, 380);
}

function setNavActive(screen) {
  document.querySelectorAll(".nav-link").forEach(a => {
    a.classList.toggle("on", a.dataset.screen === screen);
  });
}

/* ============================ rendering ============================ */

function renderAll() {
  renderGreeting();
  renderHeroStats();
  renderRecent();
  renderMemories();
  renderCalendar();
  renderMood();
  updateCounts();
}

function renderGreeting() {
  const hour = new Date().getHours();
  let g = "Good evening", icon = "\u{1F319}";
  if (hour < 12) { g = "Good morning"; icon = "\u2600\uFE0F"; }
  else if (hour < 18) { g = "Good afternoon"; icon = "\u26C5"; }
  $("#greeting").textContent = `${g}. ${icon}`;
  $("#homeReflection").textContent = "\u201C" + PROMPTS[0].q + "\u201D";
}

function updateCounts() {
  const total = state.stats ? state.stats.total : state.entries.length;
  const label = `${total} ${total === 1 ? "memory" : "memories"} preserved`;
  $("#countLabel").textContent = label;
  $("#footer").textContent = "Kept privately \u00B7 " + label;
}

function renderHeroStats() {
  const s = state.stats || { total: state.entries.length, entriesThisMonth: 0, currentStreak: 0, mostCommonMood: "" };
  const fav = s.mostCommonMood ? `${moodOf(s.mostCommonMood).glyph} ${moodOf(s.mostCommonMood).label}` : "\u2014";
  const cells = [
    { label: "Memories", value: String(s.total) },
    { label: "This month", value: String(s.entriesThisMonth) },
    { label: "Current streak", value: String(s.currentStreak).padStart(2, "0") },
    { label: "Favourite mood", value: fav }
  ];
  $("#heroStats").innerHTML = cells.map(c =>
    `<div class="stat"><b>${esc(c.label)}</b><span>${esc(c.value)}</span></div>`
  ).join("");
}

function renderRecent() {
  const recent = state.entries.slice(0, 3);
  const el = $("#recentList");
  if (recent.length === 0) { el.innerHTML = '<p class="eyebrow">Nothing kept yet.</p>'; return; }
  el.innerHTML = recent.map(e =>
    `<a href="#" class="recent-item" data-id="${e.id}">
       <span class="short">${esc(fmtShort(e.date))}</span>
       <span class="r-title">${esc(e.title || "(untitled)")}</span>
     </a>`
  ).join("");
}

function renderMemories() {
  const grid = $("#memoriesGrid");
  const empty = $("#memoriesEmpty");
  if (state.entries.length === 0) {
    grid.innerHTML = "";
    empty.style.display = "block";
    return;
  }
  empty.style.display = "none";
  grid.innerHTML = state.entries.map((e, i) => {
    const m = moodOf(e.mood);
    const bg = i % 3 === 1 ? "rgba(247,240,224,0.86)" : "rgba(255,252,244,0.62)";
    const rot = (i % 2 ? 0.7 : -0.8) + "deg";
    const offset = i % 3 === 2 ? "26px" : "0px";
    const strip = e.image
      ? `<div class="memory-strip" style="height:148px;display:flex;"><span>${esc(e.image)}</span></div>`
      : "";
    const excerptSrc = oneLine(e.entry);
    const excerpt = excerptSrc.slice(0, 108) + (excerptSrc.length > 108 ? "\u2026" : "");
    return `
      <article class="memory" data-id="${e.id}" style="background:${bg}; transform: rotate(${rot}) translateY(0); margin-top:${offset};">
        <div class="memory-tape"></div>
        ${strip}
        <div class="eyebrow">${esc(fmtFull(e.date).toUpperCase())}</div>
        <h3>${esc(e.title || "(untitled)")}</h3>
        <div class="memory-mood" style="color:${m.color}; border-color:${m.color}80;">
          <span class="glyph">${m.glyph}</span>${esc(m.label)}
        </div>
        <p class="memory-excerpt">${esc(excerpt)}</p>
        <span class="memory-read">Read memory \u2192</span>
      </article>`;
  }).join("");
}

function ensureCalMonth() {
  if (state.calYear != null) return;
  let ref = new Date();
  for (const e of state.entries) {
    const d = parseIso(e.date);
    if (d) { ref = d; break; }
  }
  state.calYear = ref.getFullYear();
  state.calMonth = ref.getMonth();
}

function turnMonth(delta) {
  if (state.calBusy) return;
  ensureCalMonth();
  const leaf = $("#calLeaf");
  const title = $("#calTitle");
  const dir = delta > 0 ? "next" : "prev";
  const apply = () => {
    state.calMonth += delta;
    while (state.calMonth < 0) { state.calMonth += 12; state.calYear--; }
    while (state.calMonth > 11) { state.calMonth -= 12; state.calYear++; }
    renderCalendar();
    title.classList.remove("ink-in");
    void title.offsetWidth;
    title.classList.add("ink-in");
  };

  if (matchMedia("(prefers-reduced-motion: reduce)").matches) {
    apply();
    return;
  }

  state.calBusy = true;
  leaf.classList.remove("turn-in-next", "turn-in-prev", "turn-out-next", "turn-out-prev");
  void leaf.offsetWidth;
  leaf.classList.add("turn-out-" + dir);
  setTimeout(() => {
    apply();
    leaf.classList.remove("turn-out-next", "turn-out-prev");
    void leaf.offsetWidth;
    leaf.classList.add("turn-in-" + dir);
    setTimeout(() => {
      leaf.classList.remove("turn-in-next", "turn-in-prev");
      state.calBusy = false;
    }, 720);
  }, 420);
}

function renderCalendar() {
  ensureCalMonth();
  const year = state.calYear;
  const month = state.calMonth;
  const byDay = new Map();
  state.entries.forEach(e => {
    const d = parseIso(e.date);
    if (d && d.getFullYear() === year && d.getMonth() === month) {
      if (!byDay.has(d.getDate())) byDay.set(d.getDate(), e);
    }
  });

  $("#calTitle").innerHTML = `${MONTHS[month]}<span>&nbsp;${year}</span>`;
  const kept = byDay.size;
  $("#calKept").textContent = `${kept} ${kept === 1 ? "day" : "days"} kept`;

  const firstDay = new Date(year, month, 1).getDay();
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const today = todayIso();

  let html = WEEKDAYS.map(w => `<div class="cal-dow">${w}</div>`).join("");
  for (let i = 0; i < firstDay; i++) html += `<div class="cal-day empty"></div>`;
  for (let n = 1; n <= daysInMonth; n++) {
    const e = byDay.get(n);
    const has = !!e;
    const m = has ? moodOf(e.mood) : null;
    const iso = `${year}-${String(month + 1).padStart(2, "0")}-${String(n).padStart(2, "0")}`;
    const isToday = iso === today;
    const bg = has ? "rgba(179,154,99,0.16)" : "rgba(255,252,244,0.35)";
    const bd = has ? "rgba(179,154,99,0.55)" : "rgba(74,52,40,0.12)";
    const fg = has ? "#2A211C" : "#8A7A68";
    const dot = has ? m.color : "transparent";
    html += `<button class="cal-day ${has ? "has" : ""} ${isToday ? "today" : ""}" ${has ? `data-id="${e.id}"` : ""}
        style="--i:${n}; background:${bg}; border-color:${bd}; color:${fg};"
        aria-label="${n} ${MONTHS[month]} ${year}${has ? ", has a memory" : ""}">
        ${n}<span class="dot" style="background:${dot};"></span>
      </button>`;
  }
  $("#calGrid").innerHTML = html;
}

function renderMood() {
  const counts = (state.stats && state.stats.moodCounts) ? state.stats.moodCounts : {};
  const total = Object.values(counts).reduce((a, b) => a + b, 0);

  // favourite mood card
  const favLabel = (state.stats && state.stats.mostCommonMood) || "";
  const fav = moodOf(favLabel);
  const favCount = counts[favLabel] || 0;
  const favPct = total ? Math.round((favCount / total) * 100) : 0;
  const favEl = $("#moodFav");
  favEl.querySelector(".mood-fav-glyph").textContent = fav.glyph;
  favEl.querySelector(".mood-fav-glyph").style.color = fav.color;
  favEl.querySelector(".mood-fav-name").textContent = favLabel || "\u2014";
  favEl.querySelector(".mood-fav-pct").textContent = favPct + "%";

  // distribution bars (moods present, sorted by count desc)
  const rows = MOODS
    .map(m => ({ m, count: counts[m.label] || 0 }))
    .filter(r => r.count > 0)
    .sort((a, b) => b.count - a.count);
  const max = rows.length ? rows[0].count : 1;
  $("#moodBars").innerHTML = rows.map(r => {
    const pct = total ? Math.round((r.count / total) * 100) : 0;
    const w = Math.round((r.count / max) * 100);
    return `<div class="bar-row">
        <div class="bar-label">${esc(r.m.label)}</div>
        <div class="bar-track"><div class="bar-fill" style="width:${w}%; background:${r.m.color}; box-shadow:0 0 12px ${r.m.color}66;"></div></div>
        <div class="bar-pct">${pct}%</div>
      </div>`;
  }).join("") || '<p class="eyebrow">No moods recorded yet.</p>';

  // 30-day emotional weather (matches the design's synthetic wave)
  const accent = "#B39A63";
  let wave = "";
  for (let i = 0; i < 30; i++) {
    const h = 34 + Math.abs(Math.sin(i * 0.7)) * 58 + (i % 4) * 4;
    const color = i % 5 === 0 ? "#B98F88" : (i % 3 === 0 ? "#87927A" : accent);
    wave += `<div class="wave-bar" style="height:${h}%; background:linear-gradient(180deg, ${color} 0%, rgba(185,143,136,0.18) 100%);"></div>`;
  }
  $("#wave").innerHTML = wave;
}

function renderReflections() {
  $("#reflGrid").innerHTML = PROMPTS.map((p, i) => {
    const rot = (i % 2 ? -0.9 : 0.8) + "deg";
    const bg = i % 3 === 1 ? "rgba(185,143,136,0.14)" : "rgba(255,252,244,0.5)";
    return `<div class="refl-card2" style="transform:rotate(${rot}); background:${bg};">
        <div class="eyebrow">${esc(p.tag)}</div>
        <p class="q">${esc(p.q)}</p>
        <a href="#" data-action="write">Write about it \u2192</a>
      </div>`;
  }).join("");
}

function renderMoodPicker() {
  $("#moodPicker").innerHTML = MOODS.map(m =>
    `<button class="mood-opt" type="button" data-mood="${esc(m.label)}" aria-pressed="false">
       <span class="glyph">${m.glyph}</span>${esc(m.label)}
     </button>`
  ).join("");
}

/* ============================ write screen ============================ */

function setWriteDate() {
  setWriteDateFrom(todayIso());
}

function setWriteDateFrom(iso) {
  const d = parseIso(iso) || new Date();
  $("#writeDate").textContent = `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
  $("#writeDay").textContent = WEEKDAYS_FULL[d.getDay()];
}

function resetWriteForm() {
  state.editing = null;
  writeJournalBody("");
  applyMood(null);
  setWriteDate();
  $("#saveEntry").textContent = "Save memory";
  updateSignOff();
}

function selectMood(label) {
  applyMood(state.mood === label ? null : label);
}

function applyMood(label) {
  state.mood = label || null;
  document.querySelectorAll(".mood-opt").forEach(b => {
    const active = b.dataset.mood === state.mood;
    b.setAttribute("aria-pressed", active ? "true" : "false");
    const m = moodOf(b.dataset.mood);
    if (active) {
      b.style.color = "#2A211C";
      b.style.background = "rgba(255,255,255,0.72)";
      b.style.borderColor = m.color;
      b.style.transform = "translateY(-5px)";
      b.style.boxShadow = `0 16px 26px -14px rgba(42,33,28,0.5), 0 0 22px ${m.color}55`;
      b.querySelector(".glyph").style.color = m.color;
    } else {
      b.style.color = "";
      b.style.background = "";
      b.style.borderColor = "";
      b.style.transform = "";
      b.style.boxShadow = "";
      b.querySelector(".glyph").style.color = "";
    }
  });
}

function updateSignOff() {
  const typed = oneLine(readJournalBody());
  $("#signOff").textContent = typed.length > 3 ? "\u2014 Me" : "\u2014 Me";
}

function deriveTitle(body) {
  const line = (body.split(/\n/)[0] || "").trim();
  if (!line) return "Untitled";
  const words = line.split(/\s+/);
  return words.slice(0, 6).join(" ");
}

async function saveEntry() {
  const body = readJournalBody().replace(/^\s+|\s+$/g, "");
  if (!body) { $("#writeBody").focus(); return; }

  const editing = state.editing;
  const payload = {
    date: editing ? editing.date : todayIso(),
    mood: state.mood || "Neutral",
    title: deriveTitle(body),
    entry: body,
    image: editing ? (editing.image || "") : ""
  };

  // fire the save ceremony
  const overlay = $("#save");
  const paper = $("#savePaper");
  const note = $("#saveNote");
  overlay.style.display = "flex";
  paper.style.transform = "rotateX(0deg) scale(1)";
  paper.style.opacity = "1";
  note.style.opacity = "0";
  note.textContent = editing ? "The page was rewritten." : "Another memory preserved.";

  setTimeout(() => { paper.style.transform = "rotateX(-26deg) scale(.94)"; note.style.opacity = "1"; }, 120);
  setTimeout(() => { paper.style.transform = "rotateX(-88deg) translateY(-40px) scale(.72)"; paper.style.opacity = "0"; }, 1700);

  // persist in parallel with the animation
  try {
    if (editing) {
      await apiSend("/api/entries/" + editing.id, "PUT", payload);
    } else {
      await apiSend("/api/entries", "POST", payload);
    }
  } catch (e) { console.error(e); }

  setTimeout(async () => {
    overlay.style.display = "none";
    resetWriteForm();
    await loadData();
    go("memories");
  }, 3400);
}

/* ============================ book open ceremony ============================ */

function openBook() {
  if (state.opening) return;
  state.opening = true;
  resetWriteForm();
  const bloom = $("#bloom");
  const scene = $("#scene");
  const hint = $("#hint");
  bloom.style.background = "rgba(255,214,150,0.42)";
  scene.style.transform = "scale(1.06)";
  hint.textContent = "opening\u2026";

  setTimeout(() => { go("write"); }, 1050);
  setTimeout(() => {
    bloom.style.background = "rgba(255,214,150,0)";
    scene.style.transform = "";
    hint.textContent = "move your cursor over the frame";
    state.opening = false;
  }, 1800);
}

/* ============================ parallax (lerp) ============================ */

function initParallax() {
  const frame = $("#frame");
  const scene = $("#scene");
  const hint = $("#hint");
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduce) return;

  let tx = 0, ty = 0, cx = 0, cy = 0, raf = null, active = false;

  function loop() {
    cx += (tx - cx) * 0.075;
    cy += (ty - cy) * 0.075;
    if (!state.opening) {
      scene.style.transform = `rotateX(${cy.toFixed(3)}deg) rotateY(${cx.toFixed(3)}deg)`;
    }
    if (Math.abs(tx - cx) > 0.01 || Math.abs(ty - cy) > 0.01) raf = requestAnimationFrame(loop);
    else raf = null;
  }
  function kick() { if (!raf) raf = requestAnimationFrame(loop); }

  frame.addEventListener("pointermove", (e) => {
    if (state.opening) return;
    const r = frame.getBoundingClientRect();
    tx =  ((e.clientX - r.left) / r.width  - 0.5) * 12;
    ty = -((e.clientY - r.top)  / r.height - 0.5) * 12;
    scene.style.transition = "none";
    if (!active) { active = true; hint.classList.add("gone"); }
    kick();
  });
  frame.addEventListener("pointerleave", () => {
    scene.style.transition = "transform .9s cubic-bezier(.16,.84,.44,1)";
    tx = ty = 0; kick();
  });
}

/* ============================ dust motes ============================ */

function initDust() {
  const dust = $("#dust");
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  for (let i = 0; i < 13; i++) {
    const m = document.createElement("div");
    m.className = "mote";
    const s = 1.6 + Math.random() * 2.6;
    m.style.width = m.style.height = s + "px";
    m.style.left = (25 + Math.random() * 70) + "%";
    m.style.top = (30 + Math.random() * 60) + "%";
    m.style.setProperty("--dx", (Math.random() * 90 - 30) + "px");
    m.style.setProperty("--dy", (-60 - Math.random() * 130) + "px");
    if (!reduce) m.style.animation = `drift ${20 + Math.random() * 26}s linear ${-Math.random() * 30}s infinite`;
    dust.appendChild(m);
  }
}

/* ============================ reader ============================ */

function openReader(id) {
  const e = state.entries.find(x => String(x.id) === String(id));
  if (!e) return;
  state.reading = e;
  const m = moodOf(e.mood);
  $("#readDate").textContent = fmtFull(e.date).toUpperCase();
  $("#readTitle").textContent = e.title || "(untitled)";
  $("#readMood").textContent = `${m.glyph}  felt ${m.label.toLowerCase()}`;
  $("#readBody").textContent = e.entry || "";
  $("#reader").style.display = "flex";
}

function closeReader() {
  state.reading = null;
  $("#reader").style.display = "none";
}

async function discardReading() {
  if (!state.reading) return;
  const id = state.reading.id;
  try {
    await apiSend("/api/entries/" + id, "DELETE");
    closeReader();
    await loadData();
  } catch (e) {
    console.error(e);
  }
}

function editReading() {
  if (!state.reading) return;
  const e = state.reading;
  closeReader();
  state.editing = e;
  writeJournalBody(e.entry || "");
  applyMood(e.mood);
  setWriteDateFrom(e.date);
  $("#saveEntry").textContent = "Keep these changes";
  updateSignOff();
  go("write");
}

/* ============================ search ============================ */

function openSearch() {
  $("#search").style.display = "flex";
  renderSearch("");
  setTimeout(() => $("#searchInput").focus(), 60);
}

function closeSearch() {
  $("#search").style.display = "none";
  $("#searchInput").value = "";
}

function renderSearch(query) {
  const q = query.trim().toLowerCase();
  const results = q
    ? state.entries.filter(e => ((e.title || "") + " " + (e.entry || "")).toLowerCase().includes(q))
    : state.entries.slice(0, 4);
  $("#searchFound").textContent = q
    ? `${results.length} ${results.length === 1 ? "memory" : "memories"} found \u00B7 \u201C${query.trim()}\u201D`
    : "Recently kept";
  $("#searchResults").innerHTML = results.map(e => {
    const excerptSrc = oneLine(e.entry);
    const excerpt = excerptSrc.slice(0, 92) + (excerptSrc.length > 92 ? "\u2026" : "");
    return `<a href="#" class="search-result" data-id="${e.id}">
        <span class="short">${esc(fmtShort(e.date))}</span>
        <span>
          <span class="sr-title">${esc(e.title || "(untitled)")}</span>
          <span class="sr-excerpt">${esc(excerpt)}</span>
        </span>
      </a>`;
  }).join("") || '<p class="search-found">Nothing found.</p>';
}

/* ============================ init ============================ */

function init() {
  renderMoodPicker();
  renderReflections();
  setWriteDate();
  setNavActive("home");
  $("#screen-home").classList.add("active");

  initParallax();
  initDust();

  // nav
  document.querySelectorAll(".nav-link").forEach(a => {
    a.addEventListener("click", (ev) => {
      ev.preventDefault();
      if (a.dataset.screen !== "write") resetWriteForm();
      go(a.dataset.screen);
    });
  });
  $("#openSearch").addEventListener("click", openSearch);
  $("#startWriting").addEventListener("click", () => { resetWriteForm(); go("write"); });

  // scene / book
  const scene = $("#scene");
  scene.addEventListener("click", openBook);
  scene.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); openBook(); }
  });

  // write
  $("#saveEntry").addEventListener("click", saveEntry);
  $("#writeBody").addEventListener("input", updateSignOff);
  $("#moodPicker").addEventListener("click", (ev) => {
    const btn = ev.target.closest(".mood-opt");
    if (btn) selectMood(btn.dataset.mood, btn);
  });

  // delegated actions (write links, close page)
  document.body.addEventListener("click", (ev) => {
    const act = ev.target.closest("[data-action]");
    if (act) {
      ev.preventDefault();
      if (act.dataset.action === "write") { resetWriteForm(); go("write"); }
      if (act.dataset.action === "home") { resetWriteForm(); go("home"); }
    }
  });

  // recent / memories / calendar / search open reader
  const openFromCard = (ev) => {
    const el = ev.target.closest("[data-id]");
    if (!el) return;
    ev.preventDefault();
    openReader(el.dataset.id);
    if ($("#search").style.display === "flex") closeSearch();
  };
  $("#recentList").addEventListener("click", openFromCard);
  $("#memoriesGrid").addEventListener("click", openFromCard);
  $("#calGrid").addEventListener("click", (ev) => {
    const el = ev.target.closest(".cal-day.has");
    if (el) { ev.preventDefault(); openReader(el.dataset.id); }
  });
  $("#searchResults").addEventListener("click", openFromCard);

  // reader
  $("#reader").addEventListener("click", (ev) => { if (ev.target.id === "reader") closeReader(); });
  $("#readDiscard").addEventListener("click", (ev) => { ev.preventDefault(); discardReading(); });
  $("#readEdit").addEventListener("click", (ev) => { ev.preventDefault(); editReading(); });

  $("#calPrev").addEventListener("click", () => turnMonth(-1));
  $("#calNext").addEventListener("click", () => turnMonth(1));

  // search
  $("#search").addEventListener("click", (ev) => { if (ev.target.id === "search") closeSearch(); });
  $("#searchInput").addEventListener("input", (ev) => renderSearch(ev.target.value));

  // keyboard
  window.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape") { closeReader(); closeSearch(); }
    if ((ev.key === "k" || ev.key === "K") && (ev.metaKey || ev.ctrlKey)) { ev.preventDefault(); openSearch(); }
    if (state.screen === "calendar") {
      const overlaysClosed = $("#reader").style.display === "none" && $("#search").style.display === "none";
      if (overlaysClosed && ev.key === "ArrowLeft") { ev.preventDefault(); turnMonth(-1); }
      if (overlaysClosed && ev.key === "ArrowRight") { ev.preventDefault(); turnMonth(1); }
    }
  });

  loadData();
}

document.addEventListener("DOMContentLoaded", init);
