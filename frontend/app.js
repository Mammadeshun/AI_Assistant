/* Financial Manager — single-page client. No build step, no dependencies. */

const state = {
  user: null,
  view: "dashboard",
  categories: [],
  accounts: [],
  ai: { enabled: false, model: "", reason: null },
  chat: { threadId: null, messages: [], busy: false },
  txFilters: { search: "", account_id: "", category_id: "", direction: "", offset: 0, limit: 50 },
};

/* ------------------------------------------------------------------ */
/* API                                                                 */
/* ------------------------------------------------------------------ */
async function api(path, { method = "GET", body, form } = {}) {
  const options = { method, headers: {}, credentials: "same-origin" };
  if (form) {
    options.body = form;
  } else if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }

  const response = await fetch(path, options);
  if (response.status === 204) return null;

  const text = await response.text();
  const payload = text ? safeJson(text) : null;

  if (!response.ok) {
    const detail = payload?.detail ?? `Request failed (${response.status})`;
    throw new ApiError(typeof detail === "string" ? detail : JSON.stringify(detail), response.status);
  }
  return payload;
}

class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

function safeJson(text) {
  try { return JSON.parse(text); } catch { return { detail: text }; }
}

/* ------------------------------------------------------------------ */
/* Formatting                                                          */
/* ------------------------------------------------------------------ */
const ZERO_DECIMAL = new Set(["JPY", "KRW", "VND", "CLP", "ISK", "HUF"]);

function money(minor, currency = state.user?.base_currency || "GBP", { signed = false } = {}) {
  const digits = ZERO_DECIMAL.has(currency) ? 0 : 2;
  const value = minor / 10 ** digits;
  const formatted = new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(Math.abs(value));
  if (!signed) return value < 0 ? `-${formatted}` : formatted;
  return `${value < 0 ? "−" : "+"}${formatted}`;
}

function shortMoney(minor, currency = state.user?.base_currency || "GBP") {
  const value = Math.abs(minor) / 100;
  if (value >= 1000) return money(Math.sign(minor) * Math.round(value / 100) * 100 * 100, currency);
  return money(minor, currency);
}

function formatDate(iso) {
  return new Date(iso + (iso.length === 10 ? "T00:00:00" : "")).toLocaleDateString(undefined, {
    day: "2-digit", month: "short", year: "numeric",
  });
}

function monthLabel(key) {
  const [year, month] = key.split("-").map(Number);
  return new Date(year, month - 1, 1).toLocaleDateString(undefined, { month: "short" });
}

function amountClass(minor) {
  return minor > 0 ? "pos" : minor < 0 ? "neg" : "";
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[char]);
}

/* ------------------------------------------------------------------ */
/* UI primitives                                                       */
/* ------------------------------------------------------------------ */
function toast(message, kind = "info") {
  const node = document.createElement("div");
  node.className = `toast ${kind}`;
  node.textContent = message;
  document.getElementById("toasts").append(node);
  setTimeout(() => node.remove(), kind === "error" ? 7000 : 4000);
}

function openModal(title, html, onMount) {
  document.getElementById("modal-title").textContent = title;
  const body = document.getElementById("modal-body");
  body.innerHTML = html;
  document.getElementById("modal-backdrop").classList.remove("hidden");
  onMount?.(body);
}

function closeModal() {
  document.getElementById("modal-backdrop").classList.add("hidden");
  document.getElementById("modal-body").innerHTML = "";
}

function confirmAction(message) {
  return window.confirm(message);
}

/* ------------------------------------------------------------------ */
/* Charts (hand-rolled SVG — no chart library needed)                  */
/* ------------------------------------------------------------------ */
function cashFlowChart(months) {
  if (!months.length) return `<p class="empty">No data yet.</p>`;

  const width = 640, height = 220, padX = 34, padY = 18;
  const peak = Math.max(1, ...months.flatMap((m) => [m.income_minor, m.expense_minor]));
  const innerW = width - padX * 2;
  const innerH = height - padY * 2 - 16;
  const groupW = innerW / months.length;
  const barW = Math.min(14, groupW / 3);

  const bars = months.map((month, index) => {
    const x = padX + groupW * index + groupW / 2;
    const incomeH = (month.income_minor / peak) * innerH;
    const expenseH = (month.expense_minor / peak) * innerH;
    const baseY = padY + innerH;
    return `
      <rect x="${x - barW - 1}" y="${baseY - incomeH}" width="${barW}" height="${Math.max(incomeH, 1)}"
            rx="2" fill="var(--positive)" opacity=".85">
        <title>${monthLabel(month.month)} in: ${money(month.income_minor)}</title>
      </rect>
      <rect x="${x + 1}" y="${baseY - expenseH}" width="${barW}" height="${Math.max(expenseH, 1)}"
            rx="2" fill="var(--negative)" opacity=".85">
        <title>${monthLabel(month.month)} out: ${money(month.expense_minor)}</title>
      </rect>
      <text x="${x}" y="${baseY + 14}" text-anchor="middle">${monthLabel(month.month)}</text>`;
  }).join("");

  const gridlines = [0, 0.5, 1].map((fraction) => {
    const y = padY + innerH - innerH * fraction;
    return `<line x1="${padX}" x2="${width - padX}" y1="${y}" y2="${y}"
                  stroke="var(--border)" stroke-dasharray="3 4" />
            <text x="4" y="${y + 3}">${shortMoney(peak * fraction)}</text>`;
  }).join("");

  return `<svg class="chart" viewBox="0 0 ${width} ${height}" role="img"
               aria-label="Monthly income and spending">${gridlines}${bars}</svg>
    <div class="inline small muted" style="margin-top:.6rem">
      <span class="pill"><span class="dot" style="background:var(--positive)"></span>Money in</span>
      <span class="pill"><span class="dot" style="background:var(--negative)"></span>Money out</span>
    </div>`;
}

function donutChart(slices) {
  const total = slices.reduce((sum, slice) => sum + slice.amount_minor, 0);
  if (!total) return `<p class="empty">Nothing spent in this period.</p>`;

  const size = 180, radius = 70, stroke = 26, centre = size / 2;
  const circumference = 2 * Math.PI * radius;
  let offset = 0;

  const arcs = slices.map((slice) => {
    const fraction = slice.amount_minor / total;
    const dash = `${fraction * circumference} ${circumference}`;
    const arc = `<circle cx="${centre}" cy="${centre}" r="${radius}" fill="none"
      stroke="${slice.colour}" stroke-width="${stroke}"
      stroke-dasharray="${dash}" stroke-dashoffset="${-offset}"
      transform="rotate(-90 ${centre} ${centre})">
      <title>${escapeHtml(slice.name)}: ${money(slice.amount_minor)} (${(fraction * 100).toFixed(1)}%)</title>
    </circle>`;
    offset += fraction * circumference;
    return arc;
  }).join("");

  const legend = slices.slice(0, 8).map((slice) => `
    <div class="row-between small">
      <span class="inline"><span class="dot" style="background:${slice.colour}"></span>
        ${slice.icon ?? ""} ${escapeHtml(slice.name)}</span>
      <span class="num">${money(slice.amount_minor)}</span>
    </div>`).join("");

  return `
    <div style="display:flex;gap:1.25rem;align-items:center;flex-wrap:wrap">
      <svg class="chart" style="width:${size}px;flex:none" viewBox="0 0 ${size} ${size}" role="img"
           aria-label="Spending by category">
        ${arcs}
        <text x="${centre}" y="${centre - 4}" text-anchor="middle" style="font-size:11px">Total</text>
        <text x="${centre}" y="${centre + 12}" text-anchor="middle"
              style="font-size:13px;fill:var(--text);font-weight:600">${money(total)}</text>
      </svg>
      <div class="stack" style="flex:1;min-width:12rem;gap:.4rem">${legend}</div>
    </div>`;
}

/* ------------------------------------------------------------------ */
/* Views                                                               */
/* ------------------------------------------------------------------ */
const views = {
  /* --- dashboard --------------------------------------------------- */
  async dashboard(root) {
    const data = await api("/api/analytics/dashboard");
    const { summary, net_worth: netWorth } = data;

    const balances = netWorth.balances.length
      ? netWorth.balances.map((b) => money(b.amount_minor, b.currency)).join(" · ")
      : money(0);

    const insights = data.insights.length
      ? data.insights.map((item) => `
          <div class="insight ${item.severity}">
            <span class="insight-icon">${{ alert: "🚨", warning: "⚠️", positive: "✅", info: "💡" }[item.severity] ?? "💡"}</span>
            <div>
              <div class="insight-title">${escapeHtml(item.title)}</div>
              <div class="insight-detail">${escapeHtml(item.detail)}</div>
            </div>
          </div>`).join("")
      : `<p class="empty">Connect an account or import a statement to see insights.</p>`;

    const budgets = data.budgets.length
      ? data.budgets.map(budgetRow).join("")
      : `<p class="empty">No budgets set. Add one from the Budgets tab.</p>`;

    const merchants = data.top_merchants.length
      ? `<div class="table-wrap"><table><tbody>${data.top_merchants.map((m) => `
          <tr><td class="trunc">${escapeHtml(m.name)}</td>
              <td class="num muted small">${m.transaction_count}×</td>
              <td class="num">${money(m.amount_minor)}</td></tr>`).join("")}</tbody></table></div>`
      : `<p class="empty">No spending this month.</p>`;

    const otherCurrencies = summary.other_currencies.length
      ? `<div class="card"><h2>Other currencies this month</h2>
          <div class="stack">${summary.other_currencies.map((row) => `
            <div class="row-between small">
              <span>${row.currency}</span>
              <span class="num ${amountClass(row.net_minor)}">${money(row.net_minor, row.currency, { signed: true })}</span>
            </div>`).join("")}</div>
          <p class="small muted" style="margin:.75rem 0 0">
            Shown separately — no exchange rates are applied to your headline totals.</p></div>`
      : "";

    root.innerHTML = `
      <div class="grid grid-4" style="margin-bottom:1rem">
        <div class="card">
          <div class="stat-label">Balance</div>
          <div class="stat-value">${balances}</div>
          <div class="stat-note">${netWorth.account_count} account(s)</div>
        </div>
        <div class="card">
          <div class="stat-label">In this month</div>
          <div class="stat-value pos">${money(summary.income_minor)}</div>
          <div class="stat-note">${summary.transaction_count} transactions</div>
        </div>
        <div class="card">
          <div class="stat-label">Out this month</div>
          <div class="stat-value neg">${money(summary.expense_minor)}</div>
          <div class="stat-note">${money(summary.average_daily_spend_minor)}/day average</div>
        </div>
        <div class="card">
          <div class="stat-label">Net</div>
          <div class="stat-value ${amountClass(summary.net_minor)}">${money(summary.net_minor, summary.currency, { signed: true })}</div>
          <div class="stat-note">Projected spend ${money(summary.projected_month_spend_minor)}</div>
        </div>
      </div>

      <div class="grid grid-2" style="margin-bottom:1rem">
        <div class="card"><h2>Cash flow · last 12 months</h2>${cashFlowChart(data.cash_flow)}</div>
        <div class="card"><h2>Where the money went</h2>${donutChart(data.by_category)}</div>
      </div>

      ${state.ai.enabled ? `
      <div class="card briefing-card" style="margin-bottom:1rem">
        <div class="card-head"><h2>Your month, in a sentence or two</h2>
          <button class="btn btn-sm" id="briefing-btn">Write it</button></div>
        <div id="briefing-body" class="muted small">
          Reads this month's totals and last month's, and tells you what changed.
        </div>
      </div>` : ""}

      <div class="grid grid-2">
        <div class="card"><h2>What stands out</h2><div class="stack">${insights}</div></div>
        <div class="card"><h2>Budgets</h2><div class="stack">${budgets}</div></div>
        <div class="card"><h2>Top merchants this month</h2>${merchants}</div>
        ${otherCurrencies}
      </div>`;

    const briefingBtn = root.querySelector("#briefing-btn");
    briefingBtn?.addEventListener("click", async () => {
      const body = root.querySelector("#briefing-body");
      briefingBtn.disabled = true;
      body.textContent = "Writing…";
      try {
        const result = await api("/api/ai/briefing", { method: "POST" });
        body.classList.remove("muted", "small");
        body.innerHTML = formatReply(result.text);
      } catch (error) {
        body.textContent = error.message;
      } finally {
        briefingBtn.disabled = false;
      }
    });
  },

  /* --- assistant ---------------------------------------------------- */
  async assistant(root) {
    if (!state.ai.enabled) {
      root.innerHTML = `
        <div class="card">
          <h2>The assistant is switched off</h2>
          <p class="muted">${escapeHtml(state.ai.reason || "No API key configured.")}</p>
          <p class="small muted">Add an Anthropic API key to your <code>.env</code> as
            <code>ANTHROPIC_API_KEY</code> and restart the app. Until you do, nothing about
            your finances is sent anywhere — every other feature works without it.</p>
        </div>`;
      return;
    }

    const threads = await api("/api/ai/threads");
    root.innerHTML = `
      <div class="chat-layout">
        <div class="card chat-card">
          <div id="chat-log" class="chat-log"></div>
          <form id="chat-form" class="chat-input">
            <input name="message" placeholder="Ask about your money…" autocomplete="off"
                   maxlength="4000" required />
            <button class="btn btn-primary" type="submit" id="chat-send">Ask</button>
          </form>
          <p class="small muted chat-note">
            Reads your transactions to answer. It cannot move money or change anything.
          </p>
        </div>
        <div class="card chat-side">
          <div class="card-head"><h2>Conversations</h2>
            <button class="btn btn-sm" id="chat-new">New</button></div>
          <div class="stack" id="thread-list">${
            threads.length ? threads.map((thread) => `
              <div class="row-between small thread-row">
                <button class="btn btn-link thread-open" data-thread="${thread.id}"
                        style="text-align:left">${escapeHtml(thread.title)}</button>
                <button class="btn btn-sm btn-danger" data-delete-thread="${thread.id}">✕</button>
              </div>`).join("") : `<p class="muted small">No conversations yet.</p>`}
          </div>
          <h3 style="margin-top:1.25rem">Try asking</h3>
          <div class="stack">${[
            "How much did I spend last month?",
            "What are my biggest subscriptions?",
            "Where did my money go this month?",
            "Am I spending more than I earn?",
            "What did I spend on groceries this year?",
          ].map((question) => `
            <button class="btn btn-sm suggestion" style="text-align:left">${escapeHtml(question)}</button>`).join("")}
          </div>
        </div>
      </div>`;

    renderChatLog();

    root.querySelector("#chat-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      const input = event.target.elements.message;
      const message = input.value.trim();
      if (!message || state.chat.busy) return;
      input.value = "";
      await sendChat(message);
    });

    root.querySelector("#chat-new").addEventListener("click", () => {
      state.chat = { threadId: null, messages: [], busy: false };
      render();
    });

    root.querySelectorAll(".suggestion").forEach((button) =>
      button.addEventListener("click", () => sendChat(button.textContent.trim())));

    root.querySelectorAll(".thread-open").forEach((button) =>
      button.addEventListener("click", async () => {
        const id = Number(button.dataset.thread);
        const messages = await api(`/api/ai/threads/${id}`);
        state.chat = { threadId: id, messages, busy: false };
        renderChatLog();
      }));

    root.querySelectorAll("[data-delete-thread]").forEach((button) =>
      button.addEventListener("click", async () => {
        await api(`/api/ai/threads/${button.dataset.deleteThread}`, { method: "DELETE" });
        if (state.chat.threadId === Number(button.dataset.deleteThread)) {
          state.chat = { threadId: null, messages: [], busy: false };
        }
        render();
      }));
  },

  /* --- transactions ------------------------------------------------ */
  async transactions(root) {
    root.innerHTML = `
      <div class="filters">
        <label class="field" style="min-width:14rem"><span>Search</span>
          <input type="search" id="f-search" placeholder="Merchant, description, reference" /></label>
        <label class="field"><span>Account</span><select id="f-account"></select></label>
        <label class="field"><span>Category</span><select id="f-category"></select></label>
        <label class="field"><span>Direction</span>
          <select id="f-direction">
            <option value="">All</option><option value="out">Money out</option><option value="in">Money in</option>
          </select></label>
        <button class="btn btn-sm" id="f-clear">Clear</button>
        <a class="btn btn-sm" href="/api/transactions/export" download>Export CSV</a>
      </div>
      <div class="card" style="padding:0">
        <div id="tx-table" class="table-wrap"><p class="empty">Loading…</p></div>
      </div>
      <div class="row-between" style="margin-top:.9rem">
        <span class="small muted" id="tx-count"></span>
        <span class="inline">
          <button class="btn btn-sm" id="tx-prev">Previous</button>
          <button class="btn btn-sm" id="tx-next">Next</button>
        </span>
      </div>`;

    fillSelect(root.querySelector("#f-account"), state.accounts, "All accounts");
    fillSelect(root.querySelector("#f-category"), state.categories, "All categories");

    const filters = state.txFilters;
    root.querySelector("#f-search").value = filters.search;
    root.querySelector("#f-account").value = filters.account_id;
    root.querySelector("#f-category").value = filters.category_id;
    root.querySelector("#f-direction").value = filters.direction;

    const reload = debounce(async () => {
      filters.search = root.querySelector("#f-search").value.trim();
      filters.account_id = root.querySelector("#f-account").value;
      filters.category_id = root.querySelector("#f-category").value;
      filters.direction = root.querySelector("#f-direction").value;
      filters.offset = 0;
      await loadTransactions();
    }, 250);

    root.querySelector("#f-search").addEventListener("input", reload);
    ["#f-account", "#f-category", "#f-direction"].forEach((selector) =>
      root.querySelector(selector).addEventListener("change", reload));
    root.querySelector("#f-clear").addEventListener("click", () => {
      state.txFilters = { search: "", account_id: "", category_id: "", direction: "", offset: 0, limit: 50 };
      render();
    });
    root.querySelector("#tx-prev").addEventListener("click", async () => {
      filters.offset = Math.max(0, filters.offset - filters.limit);
      await loadTransactions();
    });
    root.querySelector("#tx-next").addEventListener("click", async () => {
      filters.offset += filters.limit;
      await loadTransactions();
    });

    await loadTransactions();
  },

  /* --- budgets ----------------------------------------------------- */
  async budgets(root) {
    const [progress, budgets] = await Promise.all([
      api("/api/analytics/budgets"),
      api("/api/budgets"),
    ]);
    const budgeted = new Set(budgets.map((b) => b.category_id));
    const spendable = state.categories.filter((c) => c.kind === "expense");

    root.innerHTML = `
      <div class="grid grid-2">
        <div class="card">
          <div class="card-head"><h2>This month</h2></div>
          <div class="stack">${
            progress.length ? progress.map(budgetRow).join("") : `<p class="empty">No budgets yet.</p>`
          }</div>
        </div>
        <div class="card">
          <h2>Set a monthly budget</h2>
          <form id="budget-form">
            <label class="field"><span>Category</span>
              <select name="category_id" required>
                ${spendable.map((c) => `<option value="${c.id}">${c.icon ?? ""} ${escapeHtml(c.name)}${budgeted.has(c.id) ? " (update)" : ""}</option>`).join("")}
              </select></label>
            <label class="field"><span>Monthly limit (${state.user.base_currency})</span>
              <input type="number" name="amount" min="0" step="0.01" required /></label>
            <button class="btn btn-primary" type="submit">Save budget</button>
          </form>
          ${budgets.length ? `<h3 style="margin-top:1.5rem">Existing</h3>
            <div class="stack">${budgets.map((b) => {
              const category = state.categories.find((c) => c.id === b.category_id);
              return `<div class="row-between small">
                <span>${category?.icon ?? ""} ${escapeHtml(category?.name ?? "Unknown")}</span>
                <span class="inline"><span class="num">${money(b.amount_minor, b.currency)}</span>
                <button class="btn btn-sm btn-danger" data-delete-budget="${b.id}">Remove</button></span>
              </div>`;
            }).join("")}</div>` : ""}
        </div>
      </div>`;

    root.querySelector("#budget-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      const form = new FormData(event.target);
      await api("/api/budgets", {
        method: "PUT",
        body: {
          category_id: Number(form.get("category_id")),
          amount_minor: Math.round(Number(form.get("amount")) * 100),
          currency: state.user.base_currency,
          period: "monthly",
        },
      });
      toast("Budget saved", "success");
      render();
    });

    root.querySelectorAll("[data-delete-budget]").forEach((button) =>
      button.addEventListener("click", async () => {
        await api(`/api/budgets/${button.dataset.deleteBudget}`, { method: "DELETE" });
        toast("Budget removed", "success");
        render();
      }));
  },

  /* --- recurring --------------------------------------------------- */
  async recurring(root) {
    const items = await api("/api/analytics/recurring");
    const annual = items.reduce((sum, item) => sum + item.annualised_minor, 0);

    root.innerHTML = `
      <div class="grid grid-4" style="margin-bottom:1rem">
        <div class="card">
          <div class="stat-label">Detected</div>
          <div class="stat-value">${items.length}</div>
          <div class="stat-note">recurring payments</div>
        </div>
        <div class="card">
          <div class="stat-label">Annual cost</div>
          <div class="stat-value neg">${money(annual)}</div>
          <div class="stat-note">${money(Math.round(annual / 12))}/month equivalent</div>
        </div>
      </div>
      <div class="card" style="padding:0">
        <div class="table-wrap">
          ${items.length ? `<table>
            <thead><tr>
              <th>Payment</th><th>Cadence</th><th class="num">Typical</th>
              <th class="num">Per year</th><th>Last seen</th><th>Next expected</th>
            </tr></thead>
            <tbody>${items.map((item) => `
              <tr>
                <td class="trunc">${escapeHtml(item.name)}</td>
                <td><span class="pill">${item.cadence}</span></td>
                <td class="num">${money(item.typical_amount_minor, item.currency)}</td>
                <td class="num">${money(item.annualised_minor, item.currency)}</td>
                <td class="muted small">${formatDate(item.last_seen)}</td>
                <td class="muted small">${formatDate(item.next_expected)}</td>
              </tr>`).join("")}</tbody></table>`
            : `<p class="empty">Nothing recurring found yet — this needs at least three payments to the same merchant.</p>`}
        </div>
      </div>
      <p class="small muted" style="margin-top:.9rem">
        Detected from your own history: three or more payments to the same merchant at a steady interval
        and a stable amount.</p>`;
  },

  /* --- accounts ---------------------------------------------------- */
  async accounts(root) {
    const [connections, providers, accounts] = await Promise.all([
      api("/api/connections"),
      api("/api/connections/providers"),
      api("/api/accounts?include_inactive=true"),
    ]);
    state.accounts = accounts.filter((account) => account.is_active);

    root.innerHTML = `
      <div class="grid grid-2" style="margin-bottom:1rem">
        <div class="card">
          <div class="card-head"><h2>Connections</h2>
            <button class="btn btn-sm btn-primary" id="connect-btn">Connect a bank</button></div>
          <div class="stack">${
            connections.length ? connections.map((connection) => `
              <div class="row-between">
                <div>
                  <div>${escapeHtml(connection.label)}
                    <span class="badge badge-${connection.status}">${connection.status}</span></div>
                  <div class="small muted">
                    ${connection.provider === "gocardless" ? "Open Banking" : "Revolut Business API"}
                    ${connection.last_synced_at ? `· synced ${formatDate(connection.last_synced_at.slice(0, 10))}` : "· never synced"}
                    ${connection.consent_expires_at ? `· consent to ${formatDate(connection.consent_expires_at.slice(0, 10))}` : ""}
                  </div>
                  ${connection.status_detail ? `<div class="small neg">${escapeHtml(connection.status_detail)}</div>` : ""}
                </div>
                <span class="inline">
                  <button class="btn btn-sm" data-sync="${connection.id}">Sync</button>
                  <button class="btn btn-sm btn-danger" data-disconnect="${connection.id}">Disconnect</button>
                </span>
              </div>`).join("")
            : `<p class="empty">No banks connected yet.</p>`}
          </div>
        </div>

        <div class="card">
          <div class="card-head"><h2>Accounts</h2>
            <button class="btn btn-sm" id="add-account-btn">Add manual account</button></div>
          <div class="stack">${
            accounts.length ? accounts.map((account) => `
              <div class="row-between">
                <div>
                  <div>${escapeHtml(account.name)} ${account.is_active ? "" : `<span class="badge badge-pending">inactive</span>`}</div>
                  <div class="small muted">${account.currency}${account.iban_last4 ? ` ·••••${account.iban_last4}` : ""}
                    ${account.account_type ? ` · ${escapeHtml(account.account_type)}` : ""}</div>
                </div>
                <span class="inline">
                  <span class="num">${money(account.balance_minor, account.currency)}</span>
                  <button class="btn btn-sm" data-import="${account.id}">Import CSV</button>
                </span>
              </div>`).join("")
            : `<p class="empty">No accounts yet.</p>`}
          </div>
        </div>
      </div>

      <div class="card">
        <h2>Connection options on this instance</h2>
        <div class="stack">${providers.map((provider) => `
          <div class="row-between">
            <div>
              <div>${escapeHtml(provider.name)}
                ${provider.configured
                  ? `<span class="badge badge-active">ready</span>`
                  : `<span class="badge badge-pending">needs setup</span>`}</div>
              <div class="small muted">${escapeHtml(provider.description)}</div>
              ${provider.missing.length
                ? `<div class="small warn">Set in .env: ${provider.missing.join(", ")}</div>` : ""}
            </div>
          </div>`).join("")}
        </div>
      </div>`;

    root.querySelector("#connect-btn").addEventListener("click", () => connectDialog(providers));
    root.querySelector("#add-account-btn").addEventListener("click", manualAccountDialog);

    root.querySelectorAll("[data-sync]").forEach((button) =>
      button.addEventListener("click", () => syncConnection(button.dataset.sync)));
    root.querySelectorAll("[data-disconnect]").forEach((button) =>
      button.addEventListener("click", async () => {
        if (!confirmAction("Disconnect this bank? Imported history is kept.")) return;
        await api(`/api/connections/${button.dataset.disconnect}?keep_data=true`, { method: "DELETE" });
        toast("Disconnected", "success");
        render();
      }));
    root.querySelectorAll("[data-import]").forEach((button) =>
      button.addEventListener("click", () => importDialog(button.dataset.import)));
  },

  /* --- rules ------------------------------------------------------- */
  async rules(root) {
    const rules = await api("/api/rules");

    root.innerHTML = `
      <div class="grid grid-2">
        <div class="card">
          <div class="card-head"><h2>Your rules</h2>
            <span class="inline">
              ${state.ai.enabled
                ? `<button class="btn btn-sm" id="ai-categorise-btn">Ask AI to sort the rest</button>` : ""}
              <button class="btn btn-sm" id="recategorise-btn">Re-run on history</button>
            </span></div>
          ${state.ai.enabled ? `<p class="small muted" style="margin-top:-.4rem">
            Claude names the merchants the built-in rules couldn't place and saves each
            answer as a rule below — one call per new merchant, not per transaction.</p>` : ""}
          <div class="stack">${
            rules.length ? rules.map((rule) => {
              const category = state.categories.find((c) => c.id === rule.category_id);
              return `<div class="row-between">
                <div>
                  <div>${escapeHtml(rule.name)}</div>
                  <div class="small muted">
                    ${rule.field} ${rule.match_type.replace("_", " ")} “${escapeHtml(rule.pattern)}”
                    → ${category ? `${category.icon ?? ""} ${escapeHtml(category.name)}` : "no category"}
                    ${rule.mark_transfer ? " · marks as transfer" : ""}
                  </div>
                </div>
                <button class="btn btn-sm btn-danger" data-delete-rule="${rule.id}">Delete</button>
              </div>`;
            }).join("") : `<p class="empty">No rules yet. Anything you categorise by hand stays put regardless.</p>`}
          </div>
        </div>

        <div class="card">
          <h2>Add a rule</h2>
          <form id="rule-form">
            <label class="field"><span>Name</span>
              <input name="name" required placeholder="Coffee runs" /></label>
            <label class="field"><span>When</span>
              <select name="field">
                <option value="description">Description</option>
                <option value="merchant">Merchant</option>
                <option value="counterparty">Counterparty</option>
                <option value="reference">Reference</option>
              </select></label>
            <label class="field"><span>Match</span>
              <select name="match_type">
                <option value="contains">contains</option>
                <option value="starts_with">starts with</option>
                <option value="equals">equals</option>
                <option value="regex">matches regex</option>
              </select></label>
            <label class="field"><span>Pattern</span>
              <input name="pattern" required placeholder="pret a manger" /></label>
            <label class="field"><span>Category</span>
              <select name="category_id">
                <option value="">— leave uncategorised —</option>
                ${state.categories.map((c) => `<option value="${c.id}">${c.icon ?? ""} ${escapeHtml(c.name)}</option>`).join("")}
              </select></label>
            <label class="inline small" style="margin-bottom:1rem">
              <input type="checkbox" name="mark_transfer" style="width:auto" />
              Treat matches as internal transfers (excluded from spending)
            </label>
            <button class="btn btn-primary" type="submit">Add rule</button>
          </form>
        </div>
      </div>`;

    root.querySelector("#rule-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      const form = new FormData(event.target);
      try {
        await api("/api/rules", {
          method: "POST",
          body: {
            name: form.get("name"),
            field: form.get("field"),
            match_type: form.get("match_type"),
            pattern: form.get("pattern"),
            category_id: form.get("category_id") ? Number(form.get("category_id")) : null,
            mark_transfer: form.get("mark_transfer") === "on",
            priority: 50,
            is_active: true,
          },
        });
        toast("Rule added", "success");
        render();
      } catch (error) {
        toast(error.message, "error");
      }
    });

    root.querySelectorAll("[data-delete-rule]").forEach((button) =>
      button.addEventListener("click", async () => {
        await api(`/api/rules/${button.dataset.deleteRule}`, { method: "DELETE" });
        render();
      }));

    root.querySelector("#recategorise-btn").addEventListener("click", async () => {
      const result = await api("/api/transactions/recategorise", { method: "POST" });
      toast(`Re-categorised ${result.updated} transactions`, "success");
    });

    root.querySelector("#ai-categorise-btn")?.addEventListener("click", async (event) => {
      event.target.disabled = true;
      event.target.textContent = "Working…";
      try {
        const result = await api("/api/ai/categorise", { method: "POST" });
        if (!result.merchants_reviewed) {
          toast("Nothing left uncategorised", "success");
        } else {
          toast(
            `Reviewed ${result.merchants_reviewed} merchants, wrote ${result.rules_created} rules, ` +
            `re-categorised ${result.transactions_recategorised} transactions`,
            "success",
          );
        }
        render();
      } catch (error) {
        toast(error.message, "error");
        event.target.disabled = false;
        event.target.textContent = "Ask AI to sort the rest";
      }
    });
  },
};

/* ------------------------------------------------------------------ */
/* Chat                                                                */
/* ------------------------------------------------------------------ */
function renderChatLog() {
  const log = document.getElementById("chat-log");
  if (!log) return;

  if (!state.chat.messages.length && !state.chat.busy) {
    log.innerHTML = `<div class="chat-empty">
      <p>Ask anything about your own transactions — spending, trends, subscriptions,
         a merchant you don't recognise.</p>
      <p class="small muted">Answers come from your database, not from guesswork.
         Figures it can't find, it will tell you it can't find.</p>
    </div>`;
    return;
  }

  log.innerHTML = state.chat.messages.map((message) => {
    const tools = (message.tool_calls || []).length
      ? `<details class="tool-trace"><summary>Looked at ${message.tool_calls.length} source(s)</summary>
           <ul>${message.tool_calls.map((call) => `
             <li><code>${escapeHtml(call.name)}</code>
               ${Object.keys(call.input || {}).length
                 ? `<span class="muted">${escapeHtml(JSON.stringify(call.input))}</span>` : ""}
             </li>`).join("")}</ul></details>`
      : "";
    return `<div class="chat-msg ${message.role}">
      <div class="chat-bubble">${formatReply(message.content)}${tools}</div>
    </div>`;
  }).join("") + (state.chat.busy
    ? `<div class="chat-msg assistant"><div class="chat-bubble thinking">
         <span class="dot-pulse"></span> checking your transactions…</div></div>`
    : "");

  log.scrollTop = log.scrollHeight;
}

function formatReply(text) {
  // Minimal, safe rendering: escape everything, then allow paragraphs,
  // simple bullet lists and `code` spans.
  const escaped = escapeHtml(text);
  const withCode = escaped.replace(/`([^`]+)`/g, "<code>$1</code>");
  const blocks = withCode.split(/\n{2,}/).map((block) => {
    const lines = block.split("\n");
    if (lines.every((line) => /^\s*[-*]\s+/.test(line))) {
      return `<ul>${lines.map((line) => `<li>${line.replace(/^\s*[-*]\s+/, "")}</li>`).join("")}</ul>`;
    }
    return `<p>${lines.join("<br>")}</p>`;
  });
  return blocks.join("");
}

async function sendChat(message) {
  if (state.chat.busy) return;
  state.chat.busy = true;
  state.chat.messages.push({ role: "user", content: message, tool_calls: [] });
  renderChatLog();

  try {
    const response = await api("/api/ai/chat", {
      method: "POST",
      body: { message, thread_id: state.chat.threadId },
    });
    state.chat.threadId = response.thread_id;
    state.chat.messages.push({
      role: "assistant",
      content: response.reply,
      tool_calls: response.tool_calls,
    });
  } catch (error) {
    state.chat.messages.push({
      role: "assistant",
      content: `That didn't work: ${error.message}`,
      tool_calls: [],
    });
  } finally {
    state.chat.busy = false;
    renderChatLog();
  }
}

function budgetRow(budget) {
  const share = Math.min(budget.used_share, 1);
  const colour = budget.used_share >= 1 ? "var(--negative)"
    : budget.on_pace ? "var(--positive)" : "var(--warning)";
  return `
    <div>
      <div class="row-between small" style="margin-bottom:.3rem">
        <span>${escapeHtml(budget.category_name)}</span>
        <span class="num">${money(budget.spent_minor, budget.currency)} / ${money(budget.limit_minor, budget.currency)}</span>
      </div>
      <div class="bar-track"><div class="bar-fill" style="width:${share * 100}%;background:${colour}"></div></div>
      <div class="small muted" style="margin-top:.25rem">
        ${budget.remaining_minor >= 0
          ? `${money(budget.remaining_minor, budget.currency)} left`
          : `${money(-budget.remaining_minor, budget.currency)} over`}
        · ${budget.on_pace ? "on pace" : "ahead of pace"}
      </div>
    </div>`;
}

/* ------------------------------------------------------------------ */
/* Transactions table                                                  */
/* ------------------------------------------------------------------ */
async function loadTransactions() {
  const container = document.getElementById("tx-table");
  if (!container) return;

  const filters = state.txFilters;
  const params = new URLSearchParams({ limit: filters.limit, offset: filters.offset });
  if (filters.search) params.set("search", filters.search);
  if (filters.account_id) params.set("account_id", filters.account_id);
  if (filters.category_id) params.set("category_id", filters.category_id);
  if (filters.direction) params.set("direction", filters.direction);

  const page = await api(`/api/transactions?${params}`);
  const accountNames = Object.fromEntries(state.accounts.map((a) => [a.id, a.name]));

  container.innerHTML = page.items.length ? `
    <table>
      <thead><tr><th>Date</th><th>Description</th><th>Category</th><th>Account</th><th class="num">Amount</th></tr></thead>
      <tbody>${page.items.map((txn) => {
        const category = state.categories.find((c) => c.id === txn.category_id);
        return `<tr>
          <td class="muted small">${formatDate(txn.booked_at)}</td>
          <td class="trunc">${escapeHtml(txn.merchant || txn.description)}
            ${txn.state === "pending" ? `<span class="badge badge-pending">pending</span>` : ""}
            ${txn.is_transfer ? `<span class="badge">transfer</span>` : ""}</td>
          <td><select class="cat-select btn-sm" data-txn="${txn.id}">
            <option value="">— none —</option>
            ${state.categories.map((c) => `<option value="${c.id}"${c.id === txn.category_id ? " selected" : ""}>${c.icon ?? ""} ${escapeHtml(c.name)}</option>`).join("")}
          </select></td>
          <td class="muted small trunc">${escapeHtml(accountNames[txn.account_id] ?? "—")}</td>
          <td class="num ${amountClass(txn.amount_minor)}">${money(txn.amount_minor, txn.currency, { signed: true })}</td>
        </tr>`;
      }).join("")}</tbody>
    </table>` : `<p class="empty">No transactions match these filters.</p>`;

  container.querySelectorAll(".cat-select").forEach((select) =>
    select.addEventListener("change", async () => {
      try {
        await api(`/api/transactions/${select.dataset.txn}`, {
          method: "PATCH",
          body: { category_id: select.value ? Number(select.value) : null },
        });
        toast("Category updated", "success");
      } catch (error) {
        toast(error.message, "error");
      }
    }));

  const from = page.total ? filters.offset + 1 : 0;
  const to = Math.min(filters.offset + filters.limit, page.total);
  document.getElementById("tx-count").textContent = `${from}–${to} of ${page.total}`;
  document.getElementById("tx-prev").disabled = filters.offset === 0;
  document.getElementById("tx-next").disabled = to >= page.total;
}

function fillSelect(select, items, placeholder) {
  select.innerHTML = `<option value="">${placeholder}</option>` +
    items.map((item) => `<option value="${item.id}">${escapeHtml(item.name)}</option>`).join("");
}

/* ------------------------------------------------------------------ */
/* Dialogs                                                             */
/* ------------------------------------------------------------------ */
function connectDialog(providers) {
  const openBanking = providers.find((p) => p.key === "gocardless");
  const business = providers.find((p) => p.key === "revolut_business");

  openModal("Connect a bank", `
    <div class="stack">
      <div class="card">
        <h3>Revolut personal</h3>
        <p class="small muted">Read-only Open Banking connection. You approve it in the Revolut
          app; consent lasts 90 days and then needs renewing.</p>
        <label class="field"><span>Country of your Revolut account</span>
          <select id="gc-country">
            <option value="GB">United Kingdom</option><option value="IE">Ireland</option>
            <option value="LT">Lithuania</option><option value="FR">France</option>
            <option value="DE">Germany</option><option value="ES">Spain</option>
            <option value="NL">Netherlands</option><option value="PL">Poland</option>
            <option value="IT">Italy</option><option value="PT">Portugal</option>
          </select></label>
        <button class="btn btn-primary btn-block" id="gc-start" ${openBanking.configured ? "" : "disabled"}>
          ${openBanking.configured ? "Connect Revolut" : "Add GoCardless keys to .env first"}
        </button>
      </div>
      <div class="card">
        <h3>Revolut Business</h3>
        <p class="small muted">Uses Revolut's own Business API with the certificate you uploaded
          in the business portal.</p>
        <button class="btn btn-block" id="rb-start" ${business.configured ? "" : "disabled"}>
          ${business.configured ? "Connect Revolut Business" : "Add Revolut API keys to .env first"}
        </button>
      </div>
    </div>`, (body) => {
    body.querySelector("#gc-start").addEventListener("click", async (event) => {
      event.target.disabled = true;
      try {
        const response = await api("/api/connections/gocardless/start", {
          method: "POST",
          body: { country: body.querySelector("#gc-country").value, label: "Revolut" },
        });
        window.location.href = response.authorisation_url;
      } catch (error) {
        toast(error.message, "error");
        event.target.disabled = false;
      }
    });
    body.querySelector("#rb-start").addEventListener("click", async (event) => {
      event.target.disabled = true;
      try {
        const response = await api("/api/connections/revolut-business/start", {
          method: "POST", body: { label: "Revolut Business" },
        });
        window.location.href = response.authorisation_url;
      } catch (error) {
        toast(error.message, "error");
        event.target.disabled = false;
      }
    });
  });
}

function manualAccountDialog() {
  openModal("Add a manual account", `
    <form id="account-form">
      <label class="field"><span>Name</span><input name="name" required placeholder="Revolut GBP" /></label>
      <label class="field"><span>Currency</span>
        <select name="currency">
          <option>GBP</option><option>EUR</option><option>USD</option>
          <option>PLN</option><option>RON</option><option>CHF</option>
        </select></label>
      <label class="field"><span>Opening balance</span>
        <input type="number" name="balance" step="0.01" value="0" /></label>
      <button class="btn btn-primary btn-block" type="submit">Create account</button>
      <p class="small muted" style="margin-bottom:0">
        Useful for importing a CSV statement without an API connection.</p>
    </form>`, (body) => {
    body.querySelector("#account-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      const form = new FormData(event.target);
      await api("/api/accounts", {
        method: "POST",
        body: {
          name: form.get("name"),
          currency: form.get("currency"),
          account_type: "manual",
          opening_balance_minor: Math.round(Number(form.get("balance")) * 100),
        },
      });
      closeModal();
      toast("Account created", "success");
      render();
    });
  });
}

function importDialog(accountId) {
  openModal("Import a CSV statement", `
    <form id="import-form">
      <label class="field"><span>Statement file</span>
        <input type="file" name="file" accept=".csv,text/csv" required /></label>
      <label class="inline small" style="margin-bottom:1rem">
        <input type="checkbox" name="recalculate" style="width:auto" />
        Recalculate the account balance from imported transactions
      </label>
      <button class="btn btn-primary btn-block" type="submit">Import</button>
      <p class="small muted" style="margin-bottom:0">
        Works with Revolut's own CSV export. Re-importing an overlapping file is safe —
        duplicates are detected.</p>
    </form>`, (body) => {
    body.querySelector("#import-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      const form = new FormData(event.target);
      const payload = new FormData();
      payload.append("file", form.get("file"));
      payload.append("recalculate_balance", form.get("recalculate") === "on" ? "true" : "false");
      try {
        const result = await api(`/api/accounts/${accountId}/import`, { method: "POST", form: payload });
        closeModal();
        toast(`Imported ${result.added} new of ${result.parsed} rows`, "success");
        render();
      } catch (error) {
        toast(error.message, "error");
      }
    });
  });
}

/* ------------------------------------------------------------------ */
/* Sync                                                                */
/* ------------------------------------------------------------------ */
async function syncConnection(connectionId) {
  const statusNode = document.getElementById("sync-status");
  statusNode.textContent = "Syncing…";
  try {
    const path = connectionId ? `/api/connections/${connectionId}/sync` : "/api/connections/sync-all";
    const result = await api(path, { method: "POST" });
    if (result.errors.length) {
      result.errors.forEach((error) => toast(error, "error"));
      statusNode.textContent = "Sync finished with errors";
    } else {
      toast(`Added ${result.transactions_added} transactions`, "success");
      statusNode.textContent = `Synced ${new Date().toLocaleTimeString()}`;
    }
    await loadReferenceData();
    render();
  } catch (error) {
    toast(error.message, "error");
    statusNode.textContent = "Sync failed";
  }
}

/* ------------------------------------------------------------------ */
/* Shell                                                               */
/* ------------------------------------------------------------------ */
async function loadReferenceData() {
  const [categories, accounts, ai] = await Promise.all([
    api("/api/categories"),
    api("/api/accounts"),
    api("/api/ai/status").catch(() => ({ enabled: false, reason: "AI status unavailable" })),
  ]);
  state.categories = categories;
  state.accounts = accounts;
  state.ai = ai;
}

async function render() {
  const root = document.getElementById("view");
  document.getElementById("view-title").textContent =
    { dashboard: "Dashboard", assistant: "Assistant", transactions: "Transactions",
      budgets: "Budgets", recurring: "Recurring payments",
      accounts: "Accounts & connections", rules: "Rules" }[state.view];

  root.innerHTML = `<p class="empty">Loading…</p>`;
  try {
    await views[state.view](root);
  } catch (error) {
    if (error.status === 401) return showAuth();
    root.innerHTML = `<div class="card"><p class="neg">${escapeHtml(error.message)}</p></div>`;
  }
}

function showAuth(registered = true) {
  document.getElementById("app").classList.add("hidden");
  document.getElementById("auth-screen").classList.remove("hidden");
  document.getElementById("register-only").classList.toggle("hidden", registered);
  document.getElementById("auth-submit").textContent = registered ? "Sign in" : "Create your account";
  document.getElementById("auth-sub").textContent = registered
    ? "Sign in to your instance."
    : "Set up the account for this instance.";
  document.getElementById("auth-form").dataset.mode = registered ? "login" : "register";
}

async function showApp() {
  document.getElementById("auth-screen").classList.add("hidden");
  document.getElementById("app").classList.remove("hidden");
  await loadReferenceData();
  await render();
}

/* ------------------------------------------------------------------ */
/* Installable app (PWA)                                               */
/* ------------------------------------------------------------------ */
function setupInstall() {
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js").catch(() => {
      /* offline shell is a bonus, not a requirement */
    });
  }

  let deferredPrompt = null;
  const button = document.getElementById("install-btn");

  window.addEventListener("beforeinstallprompt", (event) => {
    event.preventDefault();
    deferredPrompt = event;
    button.classList.remove("hidden");
  });

  button.addEventListener("click", async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    await deferredPrompt.userChoice;
    deferredPrompt = null;
    button.classList.add("hidden");
  });

  window.addEventListener("appinstalled", () => button.classList.add("hidden"));
}

async function boot() {
  setupInstall();
  document.getElementById("modal-close").addEventListener("click", closeModal);
  document.getElementById("modal-backdrop").addEventListener("click", (event) => {
    if (event.target.id === "modal-backdrop") closeModal();
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closeModal();
  });

  document.querySelectorAll(".nav-item").forEach((button) =>
    button.addEventListener("click", () => {
      document.querySelectorAll(".nav-item").forEach((item) => item.classList.remove("active"));
      button.classList.add("active");
      state.view = button.dataset.view;
      render();
    }));

  document.getElementById("sync-btn").addEventListener("click", () => syncConnection(null));
  document.getElementById("logout-btn").addEventListener("click", async () => {
    await api("/api/auth/logout", { method: "POST" });
    window.location.reload();
  });

  document.getElementById("auth-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = new FormData(event.target);
    const mode = event.target.dataset.mode;
    const errorNode = document.getElementById("auth-error");
    errorNode.classList.add("hidden");

    try {
      const body = { email: form.get("email"), password: form.get("password") };
      if (mode === "register") {
        body.display_name = form.get("display_name") || null;
        body.base_currency = form.get("base_currency");
      }
      state.user = await api(`/api/auth/${mode}`, { method: "POST", body });
      await showApp();
    } catch (error) {
      errorNode.textContent = error.message;
      errorNode.classList.remove("hidden");
    }
  });

  try {
    state.user = await api("/api/auth/me");
    await showApp();
  } catch {
    const status = await api("/api/auth/status").catch(() => ({ registered: true }));
    showAuth(status.registered);
  }
}

function debounce(fn, wait) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), wait);
  };
}

boot();
