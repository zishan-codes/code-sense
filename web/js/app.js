/* =========================================================
   CodeSense frontend — vanilla JS
   Talks ONLY to the existing REST API. No business logic here.
   ========================================================= */

/* ---------------- CONFIG ---------------- */
const CONFIG = {
  API_BASE: "http://localhost:8080/api",
  HEALTH_POLL_MS: 15000,
  HISTORY_LIMIT: 20,
  ANALYTICS_LIMIT: 50,
};

const THEME_STORAGE_KEY = "codesense-theme";
const DEFAULT_THEME = "aurora";

const EXAMPLE_CLASS_NAME = "Main";
const EXAMPLE_SOURCE = `public class Main {
    public static void main(String[] args) {
        int a = 10;
        int b = 0;
        System.out.println("Result: " + (a / b));
    }
}
`;

/* ---------------- STATE ---------------- */
const state = {
  view: "evaluate",
  evaluating: false,
  historyItems: [],
  selectedHistoryId: null,
};

/* ---------------- DOM ---------------- */
const dom = {
  tabs: document.querySelectorAll(".tab"),
  tabIndicator: document.querySelector(".tab-indicator"),
  views: {
    evaluate: document.getElementById("view-evaluate"),
    history: document.getElementById("view-history"),
    analytics: document.getElementById("view-analytics"),
  },
  connDot: document.getElementById("connDot"),
  connText: document.getElementById("connText"),

  themeButtons: document.querySelectorAll("[data-theme-btn]"),

  classNameInput: document.getElementById("classNameInput"),
  codeEditor: document.getElementById("codeEditor"),
  lineNumbers: document.getElementById("lineNumbers"),
  editorMeta: document.getElementById("editorMeta"),
  resetExampleBtn: document.getElementById("resetExampleBtn"),

  evaluateBtn: document.getElementById("evaluateBtn"),
  evaluateBtnIcon: document.getElementById("evaluateBtnIcon"),
  evaluateBtnLabel: document.getElementById("evaluateBtnLabel"),

  resultBody: document.getElementById("resultBody"),
  durationChip: document.getElementById("durationChip"),

  historyList: document.getElementById("historyList"),
  refreshHistoryBtn: document.getElementById("refreshHistoryBtn"),
  historyDetailBody: document.getElementById("historyDetailBody"),
  historyDurationChip: document.getElementById("historyDurationChip"),

  statTotal: document.getElementById("statTotal"),
  statSuccessRate: document.getElementById("statSuccessRate"),
  statAvgDuration: document.getElementById("statAvgDuration"),
  statAiAvail: document.getElementById("statAiAvail"),
  statusBars: document.getElementById("statusBars"),
  statusLegend: document.getElementById("statusLegend"),
  aiStack: document.getElementById("aiStack"),
  aiLegend: document.getElementById("aiLegend"),
  analyticsEmpty: document.getElementById("analyticsEmpty"),
  analyticsGrid: document.querySelector(".analytics-grid"),

  toastStack: document.getElementById("toastStack"),
};

/* ---------------- API ---------------- */
const Api = {
  async health() {
    const res = await fetch(`${CONFIG.API_BASE}/health`);
    if (!res.ok) throw new ApiError(res.status, "Health check failed");
    return res.json();
  },

  async evaluate(className, sourceCode) {
    const res = await fetch(`${CONFIG.API_BASE}/evaluate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ className, sourceCode }),
    });
    const body = await safeJson(res);
    if (!res.ok) throw new ApiError(res.status, body?.error || "Evaluation request failed");
    return body;
  },

  async recentHistory(limit) {
    const res = await fetch(`${CONFIG.API_BASE}/history?limit=${encodeURIComponent(limit)}`);
    const body = await safeJson(res);
    if (!res.ok) throw new ApiError(res.status, body?.error || "Could not load history");
    return body.evaluations || [];
  },

  async historyById(id) {
    const res = await fetch(`${CONFIG.API_BASE}/history/${encodeURIComponent(id)}`);
    const body = await safeJson(res);
    if (res.status === 404) return null;
    if (!res.ok) throw new ApiError(res.status, body?.error || "Could not load evaluation");
    return body;
  },

  async analytics(limit) {
    const res = await fetch(`${CONFIG.API_BASE}/analytics?limit=${encodeURIComponent(limit)}`);
    const body = await safeJson(res);
    if (!res.ok) throw new ApiError(res.status, body?.error || "Could not load analytics");
    return body;
  },
};

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function safeJson(res) {
  try { return await res.json(); } catch { return null; }
}

/* ---------------- UTILS ---------------- */
const Utils = {
  escapeHtml(str) {
    if (str === null || str === undefined) return "";
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  },

  formatDuration(ms) {
    if (ms === null || ms === undefined || isNaN(ms)) return "—";
    if (ms < 1000) return `${Math.round(ms)} ms`;
    return `${(ms / 1000).toFixed(2)} s`;
  },

  formatTimestamp(iso) {
    if (!iso) return "";
    try {
      const d = new Date(iso);
      return d.toLocaleString(undefined, {
        month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
      });
    } catch { return iso; }
  },

  statusIcon(status) {
    const icons = {
      SUCCESS: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"></polyline></svg>`,
      COMPILE_ERROR: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M12 9v4M12 17h.01M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path></svg>`,
      RUNTIME_ERROR: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6"><circle cx="12" cy="12" r="9"></circle><path d="M15 9l-6 6M9 9l6 6"></path></svg>`,
      TIMEOUT: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 3"></path></svg>`,
    };
    return icons[status] || icons.RUNTIME_ERROR;
  },

  statusMeta(status) {
    const map = {
      SUCCESS: { label: "Success", cls: "status-SUCCESS" },
      COMPILE_ERROR: { label: "Compile Error", cls: "status-COMPILE_ERROR" },
      RUNTIME_ERROR: { label: "Runtime Error", cls: "status-RUNTIME_ERROR" },
      TIMEOUT: { label: "Timeout", cls: "status-TIMEOUT" },
    };
    return map[status] || { label: status || "Unknown", cls: "status-RUNTIME_ERROR" };
  },

  animateCounter(el, targetValue, opts = {}) {
    const isInt = opts.decimals === undefined;
    const decimals = opts.decimals || 0;
    const suffix = opts.suffix || "";
    const duration = 700;
    const start = performance.now();
    const from = 0;
    function tick(now) {
      const p = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - p, 3);
      const value = from + (targetValue - from) * eased;
      el.textContent = (isInt ? Math.round(value) : value.toFixed(decimals)) + suffix;
      if (p < 1) requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);
  },

  toast(message, type = "info") {
    const el = document.createElement("div");
    el.className = `toast ${type}`;
    el.textContent = message;
    dom.toastStack.appendChild(el);
    setTimeout(() => {
      el.classList.add("leaving");
      setTimeout(() => el.remove(), 300);
    }, 3800);
  },
};

/* ---------------- THEME ---------------- */
function applyTheme(name) {
  document.documentElement.setAttribute("data-theme", name);
  dom.themeButtons.forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-theme-btn") === name);
  });
  try { localStorage.setItem(THEME_STORAGE_KEY, name); } catch { /* localStorage unavailable — theme just won't persist */ }
}

function initTheme() {
  let saved = DEFAULT_THEME;
  try { saved = localStorage.getItem(THEME_STORAGE_KEY) || DEFAULT_THEME; } catch { /* ignore */ }
  applyTheme(saved);

  dom.themeButtons.forEach((btn) => {
    btn.addEventListener("click", () => applyTheme(btn.getAttribute("data-theme-btn")));
  });
}

/* ---------------- RENDER: Evaluate ---------------- */
function renderSkeleton(container) {
  container.innerHTML = `
    <div class="skeleton">
      <div class="bar" style="width:35%"></div>
      <div class="bar" style="width:90%"></div>
      <div class="bar" style="width:75%"></div>
      <div class="bar" style="width:60%"></div>
    </div>`;
}

function buildResultHtml(record) {
  const meta = Utils.statusMeta(record.status);

  let html = `<span class="status-badge ${meta.cls}">${Utils.statusIcon(record.status)}${meta.label}</span>`;

  html += `<div class="result-section">
    <div class="result-section-head">
      <span class="label">Program Output</span>
      ${record.stdout ? `<button class="copy-btn" data-copy="stdout">Copy</button>` : ""}
    </div>
    <div class="code-block" id="stdoutBlock">${record.stdout ? Utils.escapeHtml(record.stdout) : "<em style='color:var(--text-tertiary)'>No output produced.</em>"}</div>
  </div>`;

  if (record.stderr) {
    html += `<div class="result-section">
      <div class="result-section-head">
        <span class="label">Compiler / Runtime Error</span>
        <button class="copy-btn" data-copy="stderr">Copy</button>
      </div>
      <div class="code-block error" id="stderrBlock">${Utils.escapeHtml(record.stderr)}</div>
    </div>`;
  }

  if (record.aiRequestStatus === "AVAILABLE" && record.aiHint) {
    html += `<div class="result-section">
      <div class="ai-card">
        <div class="ai-card-head">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><path d="M12 16v-4M12 8h.01"></path></svg>
          AI Diagnostic
        </div>
        <p>${Utils.escapeHtml(record.aiHint)}</p>
        ${record.aiSuggestedFix ? `<div class="fix-label">Suggested Direction</div><p>${Utils.escapeHtml(record.aiSuggestedFix)}</p>` : ""}
      </div>
    </div>`;
  } else if (record.aiRequestStatus === "UNAVAILABLE") {
    html += `<div class="result-section">
      <div class="ai-card unavailable">
        <div class="ai-card-head">AI Diagnostic</div>
        <p>${Utils.escapeHtml(record.aiHint || "AI diagnostics are currently unavailable.")}</p>
      </div>
    </div>`;
  }

  return html;
}

function renderEvaluateResult(record) {
  dom.resultBody.innerHTML = buildResultHtml(record);
  dom.durationChip.hidden = false;
  dom.durationChip.textContent = Utils.formatDuration(record.durationMillis);
  attachCopyHandlers(dom.resultBody, record);
}

function attachCopyHandlers(container, record) {
  container.querySelectorAll(".copy-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const field = btn.getAttribute("data-copy");
      const text = record[field] || "";
      try {
        await navigator.clipboard.writeText(text);
        btn.textContent = "Copied!";
        btn.classList.add("copied");
        setTimeout(() => { btn.textContent = "Copy"; btn.classList.remove("copied"); }, 1500);
      } catch {
        Utils.toast("Could not copy to clipboard.", "error");
      }
    });
  });
}

/* ---------------- Editor: line numbers ---------------- */
function refreshLineNumbers() {
  const lines = dom.codeEditor.value.split("\n").length;
  let out = "";
  for (let i = 1; i <= lines; i++) out += i + "\n";
  dom.lineNumbers.textContent = out;
  dom.editorMeta.textContent = `${lines} line${lines === 1 ? "" : "s"}`;
}

function syncEditorScroll() {
  dom.lineNumbers.scrollTop = dom.codeEditor.scrollTop;
}

function loadExample() {
  dom.classNameInput.value = EXAMPLE_CLASS_NAME;
  dom.codeEditor.value = EXAMPLE_SOURCE;
  refreshLineNumbers();
}

/* ---------------- RENDER: History ---------------- */
function renderHistoryList(items) {
  if (!items.length) {
    dom.historyList.innerHTML = `<li class="empty-state" style="min-height:160px">
      <p>No evaluations yet.<br/>Run one from the Evaluate tab.</p></li>`;
    return;
  }

  dom.historyList.innerHTML = items.map((item, i) => {
    const meta = Utils.statusMeta(item.status);
    return `
      <li class="history-item ${item.evaluationId === state.selectedHistoryId ? "selected" : ""}"
          data-id="${item.evaluationId}" style="animation-delay:${i * 40}ms">
        <div class="history-item-top">
          <span class="history-item-class">${Utils.escapeHtml(item.className)}</span>
          <span class="mini-badge ${meta.cls}">${meta.label}</span>
        </div>
        <div class="history-item-meta">
          <span>${Utils.formatTimestamp(item.timestamp)}</span>
          <span>•</span>
          <span>${Utils.formatDuration(item.durationMillis)}</span>
        </div>
      </li>`;
  }).join("");

  dom.historyList.querySelectorAll(".history-item").forEach((el) => {
    el.addEventListener("click", () => selectHistoryItem(el.getAttribute("data-id")));
  });
}

async function selectHistoryItem(id) {
  state.selectedHistoryId = id;
  renderHistoryList(state.historyItems);
  renderSkeleton(dom.historyDetailBody);
  dom.historyDurationChip.hidden = true;

  try {
    const record = await Api.historyById(id);
    if (!record) {
      dom.historyDetailBody.innerHTML = `<div class="empty-state"><p>That evaluation could not be found.</p></div>`;
      return;
    }
    dom.historyDetailBody.innerHTML = buildResultHtml(record);
    dom.historyDurationChip.hidden = false;
    dom.historyDurationChip.textContent = Utils.formatDuration(record.durationMillis);
    attachCopyHandlers(dom.historyDetailBody, record);
  } catch (err) {
    dom.historyDetailBody.innerHTML = `<div class="empty-state"><p>Could not load this evaluation.</p></div>`;
    Utils.toast(err.message || "Failed to load evaluation details.", "error");
  }
}

async function loadHistory() {
  dom.historyList.innerHTML = `<li><div class="skeleton" style="padding:10px"><div class="bar" style="width:70%"></div><div class="bar" style="width:50%"></div></div></li>`;
  try {
    const items = await Api.recentHistory(CONFIG.HISTORY_LIMIT);
    state.historyItems = items;
    renderHistoryList(items);
  } catch (err) {
    dom.historyList.innerHTML = `<li class="empty-state" style="min-height:160px"><p>Could not load history.</p></li>`;
    Utils.toast(err.message || "Failed to load history.", "error");
  }
}

/* ---------------- RENDER: Analytics ---------------- */
function renderAnalytics(stats) {
  const total = stats.totalEvaluations || 0;

  if (total === 0) {
    dom.analyticsGrid.querySelectorAll(".stat-card, .dist-panel, .ai-panel").forEach(el => el.style.display = "none");
    dom.analyticsEmpty.hidden = false;
    return;
  }
  dom.analyticsGrid.querySelectorAll(".stat-card, .dist-panel, .ai-panel").forEach(el => el.style.display = "");
  dom.analyticsEmpty.hidden = true;

  Utils.animateCounter(dom.statTotal, total);
  Utils.animateCounter(dom.statSuccessRate, stats.successRate * 100, { decimals: 0, suffix: "%" });
  dom.statAvgDuration.textContent = Utils.formatDuration(stats.averageDurationMillis);

  const aiTotal = stats.aiRequestedCount;
  if (aiTotal > 0) {
    const pct = Math.round((stats.aiAvailableCount / aiTotal) * 100);
    dom.statAiAvail.textContent = `${pct}%`;
  } else {
    dom.statAiAvail.textContent = "N/A";
  }

  const statusData = [
    { label: "Success", count: stats.successfulEvaluations, color: "var(--teal)" },
    { label: "Compile Error", count: stats.compileErrorCount, color: "var(--amber)" },
    { label: "Runtime Error", count: stats.runtimeErrorCount, color: "var(--coral)" },
    { label: "Timeout", count: stats.timeoutCount, color: "var(--violet)" },
  ];
  const maxCount = Math.max(1, ...statusData.map(s => s.count));

  dom.statusBars.innerHTML = statusData.map(s => `
    <div class="bar-col">
      <span class="bar-count">${s.count}</span>
      <div class="bar" style="background:${s.color}; height:0"></div>
    </div>`).join("");

  requestAnimationFrame(() => {
    dom.statusBars.querySelectorAll(".bar").forEach((el, i) => {
      const pct = (statusData[i].count / maxCount) * 100;
      el.style.height = `${Math.max(4, pct)}%`;
    });
  });

  dom.statusLegend.innerHTML = statusData.map(s =>
    `<li><span class="swatch" style="background:${s.color}"></span>${s.label} (${s.count})</li>`
  ).join("");

  const notRequested = Math.max(0, total - aiTotal);
  const aiSegments = [
    { label: "Available", count: stats.aiAvailableCount, color: "var(--teal)" },
    { label: "Unavailable", count: stats.aiUnavailableCount, color: "var(--text-tertiary)" },
    { label: "Not Requested", count: notRequested, color: "var(--border)" },
  ];
  dom.aiStack.innerHTML = aiSegments.map(s =>
    `<div class="seg" style="background:${s.color}; width:0"></div>`
  ).join("");
  requestAnimationFrame(() => {
    dom.aiStack.querySelectorAll(".seg").forEach((el, i) => {
      el.style.width = `${(aiSegments[i].count / total) * 100}%`;
    });
  });
  dom.aiLegend.innerHTML = aiSegments.map(s =>
    `<li><span class="swatch" style="background:${s.color}"></span>${s.label} (${s.count})</li>`
  ).join("");
}

async function loadAnalytics() {
  try {
    const stats = await Api.analytics(CONFIG.ANALYTICS_LIMIT);
    renderAnalytics(stats);
  } catch (err) {
    Utils.toast(err.message || "Failed to load analytics.", "error");
  }
}

/* ---------------- Evaluate flow ---------------- */
async function runEvaluation() {
  if (state.evaluating) return;

  const className = dom.classNameInput.value.trim();
  const sourceCode = dom.codeEditor.value;

  if (!className || !sourceCode.trim()) {
    Utils.toast("Please provide both a class name and source code.", "error");
    return;
  }

  state.evaluating = true;
  dom.evaluateBtn.disabled = true;
  dom.evaluateBtnLabel.textContent = "Evaluating…";
  dom.evaluateBtnIcon.classList.add("spin");
  dom.evaluateBtnIcon.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9" stroke-dasharray="42" stroke-dashoffset="14"></circle></svg>`;
  dom.durationChip.hidden = true;
  renderSkeleton(dom.resultBody);

  try {
    const record = await Api.evaluate(className, sourceCode);
    renderEvaluateResult(record);
    if (record.status === "SUCCESS") Utils.toast("Evaluation completed successfully.", "success");
  } catch (err) {
    dom.resultBody.innerHTML = `<div class="empty-state"><p>${Utils.escapeHtml(err.message || "Evaluation failed.")}</p></div>`;
    Utils.toast(err.message || "Evaluation failed.", "error");
  } finally {
    state.evaluating = false;
    dom.evaluateBtn.disabled = false;
    dom.evaluateBtnLabel.textContent = "Evaluate";
    dom.evaluateBtnIcon.classList.remove("spin");
    dom.evaluateBtnIcon.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><polygon points="6 4 20 12 6 20 6 4"></polygon></svg>`;
  }
}

/* ---------------- Navigation ---------------- */
function switchView(viewName) {
  state.view = viewName;
  Object.entries(dom.views).forEach(([name, el]) => el.classList.toggle("active", name === viewName));

  dom.tabs.forEach((tab) => {
    const isActive = tab.dataset.view === viewName;
    tab.classList.toggle("active", isActive);
    tab.setAttribute("aria-selected", String(isActive));
  });

  const activeTab = [...dom.tabs].find(t => t.dataset.view === viewName);
  if (activeTab) {
    dom.tabIndicator.style.width = `${activeTab.offsetWidth}px`;
    dom.tabIndicator.style.transform = `translateX(${activeTab.offsetLeft - 4}px)`;
  }

  if (viewName === "history") loadHistory();
  if (viewName === "analytics") loadAnalytics();
}

/* ---------------- Connection health ---------------- */
async function checkHealth() {
  try {
    const data = await Api.health();
    const isUp = data && data.status === "UP";
    dom.connDot.className = `conn-dot ${isUp ? "online" : "offline"}`;
    dom.connText.textContent = isUp ? "System Online" : "Connection Error";
  } catch {
    dom.connDot.className = "conn-dot offline";
    dom.connText.textContent = "Connection Error";
  }
}

/* ---------------- Init ---------------- */
function init() {
  initTheme();
  loadExample();

  dom.tabs.forEach((tab) => {
    tab.addEventListener("click", () => switchView(tab.dataset.view));
  });
  window.addEventListener("resize", () => switchView(state.view));

  dom.codeEditor.addEventListener("input", refreshLineNumbers);
  dom.codeEditor.addEventListener("scroll", syncEditorScroll);
  dom.codeEditor.addEventListener("keydown", (e) => {
    if (e.key === "Tab") {
      e.preventDefault();
      const start = dom.codeEditor.selectionStart;
      const end = dom.codeEditor.selectionEnd;
      dom.codeEditor.value = dom.codeEditor.value.slice(0, start) + "    " + dom.codeEditor.value.slice(end);
      dom.codeEditor.selectionStart = dom.codeEditor.selectionEnd = start + 4;
      refreshLineNumbers();
    }
  });

  dom.resetExampleBtn.addEventListener("click", loadExample);
  dom.evaluateBtn.addEventListener("click", runEvaluation);
  dom.refreshHistoryBtn.addEventListener("click", loadHistory);

  checkHealth();
  setInterval(checkHealth, CONFIG.HEALTH_POLL_MS);

  requestAnimationFrame(() => switchView("evaluate"));
}

document.addEventListener("DOMContentLoaded", init);