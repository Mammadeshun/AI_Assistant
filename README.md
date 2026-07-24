# Financial Manager

A self-hosted personal finance manager that connects to Revolut. It pulls your
accounts and transactions in, categorises them, tracks budgets, spots recurring
payments, and shows you where the money actually goes.

Everything runs on your own machine. Your data lives in a local SQLite file and
is never sent anywhere except to the bank API you connect.

![Dashboard](docs/dashboard.png)

## What it does

- **Connects to Revolut** — personal accounts via Open Banking, business accounts
  via Revolut's own API, or neither (CSV import works standalone).
- **Categorises automatically** — merchant keywords, Revolut's own transaction
  types and MCC codes, plus your own rules. Anything you set by hand stays set.
- **Budgets** — monthly limits per category, with pace tracking so you know
  whether you're on course halfway through the month.
- **Recurring payments** — finds subscriptions and standing costs in your own
  history and totals what they cost you per year.
- **Insights** — plain-language notes on the dashboard: spending up vs last
  month, categories that jumped, budgets blown, cash flow negative.
- **Search, edit, export** — full transaction history, filterable, exportable
  back out to CSV.

## Getting started

```bash
./run.sh
```

That creates a virtualenv, installs dependencies, generates an `.env` with fresh
secrets, and starts the app on <http://localhost:8000>. Open it and create your
account — the first sign-up claims the instance, and registration then closes.

Prefer to do it by hand:

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
python -m backend.app.security --new-key     # → ENCRYPTION_KEY
python -m backend.app.security --new-secret  # → SESSION_SECRET
uvicorn backend.app.main:app --reload
```

You can use the app immediately with CSV import. Connecting a bank needs a few
more minutes of setup, below.

## Connecting Revolut

### Personal Revolut account

Revolut has no public API for retail customers. It is, however, a regulated bank
under UK/EU Open Banking, so a licensed account-information provider can read it
with your consent. This app uses **GoCardless Bank Account Data**, which has a
free tier that covers Revolut in the UK and across the EEA.

1. Sign up at <https://bankaccountdata.gocardless.com/>.
2. Go to **Developers → User secrets** and create a secret ID + key.
3. Add `http://localhost:8000/api/connections/gocardless/callback` to the allowed
   redirect URIs on your account.
4. Put the ID, key and redirect URI in `.env`.
5. Restart the app, then **Accounts → Connect a bank → Connect Revolut**. You'll
   be sent to Revolut to approve read-only access.

Two things to know about this route:

- **Consent expires after 90 days.** That's the regulatory maximum, not a choice
  this app makes. The connection will show as `expired` and you reconnect.
- **Production rate limit: 4 calls per account per day.** Sync once or twice a
  day, not on a loop.

### Revolut Business account

1. In the Revolut Business portal, go to **Settings → API**, upload an X.509
   certificate, and note the client ID.
2. Set `REVOLUT_CLIENT_ID`, `REVOLUT_PRIVATE_KEY_PATH` (the private key matching
   that certificate) and `REVOLUT_REDIRECT_URI` in `.env`.
3. Leave `REVOLUT_ENVIRONMENT=sandbox` until you've tried it, then switch to
   `production`.
4. **Accounts → Connect a bank → Connect Revolut Business**.

Access tokens last ~40 minutes and refresh automatically. The refresh token dies
with the certificate (90 days by default), after which you re-authorise.

### CSV import

Works with no setup at all. In the Revolut app: **Statement → Excel/CSV →
Download**. Then **Accounts → Add manual account**, and **Import CSV** on it.

Re-importing an overlapping file is safe — rows are deduplicated on a hash of
date, amount, description and reference, so you never get doubles.

## How categorisation works

Three layers, first match wins:

1. **Your rules** — created under *Rules*, applied in priority order.
2. **Provider hints** — Revolut's transaction type and merchant category code.
   Vague ones (`topup`, `transfer`, `exchange`) only apply if nothing else matches,
   so a salary arriving as a TOPUP is still filed as Salary.
3. **Keyword heuristics** — a built-in merchant list, matched whole-word.

Editing a category in the UI locks that transaction: later re-categorisation
passes leave it alone unless you explicitly ask to overwrite hand-set categories.

## Multi-currency

Balances and totals are grouped **per currency** and never combined using an
invented exchange rate. Your base currency is the headline; other currencies are
listed separately on the dashboard. If you want them converted, that's a
deliberate feature to add — it needs a rate source and a decision about which
day's rate applies.

## Security

- Bank tokens are encrypted with Fernet (AES-128-CBC + HMAC) before they touch
  disk, keyed by `ENCRYPTION_KEY`.
- Passwords are PBKDF2-SHA256, 240k rounds, per-user salt.
- Sessions are opaque random tokens; only an HMAC of each is stored, in an
  httpOnly cookie. Set `COOKIE_SECURE=true` when serving over HTTPS.
- OAuth callbacks are CSRF-protected with single-use state tokens that expire
  after 20 minutes.
- Every connection is **read-only**. This app cannot move your money — no
  payment-initiation scope is requested anywhere.
- `.env`, `data/`, `secrets/` and `*.pem` are gitignored. Don't commit them.

If you expose this beyond localhost, put it behind HTTPS and a reverse proxy.
It's built as a single-user personal instance, not a multi-tenant service.

## Layout

```
backend/app/
  main.py            FastAPI app, static file serving
  config.py          settings from .env
  models.py          SQLAlchemy schema (money stored as integer minor units)
  security.py        password hashing, sessions, credential encryption
  providers/
    base.py            provider interface + normalised shapes
    gocardless.py      Revolut personal, via Open Banking
    revolut_business.py Revolut Business API
    csv_import.py      statement parsing
  services/
    sync.py            fetch → dedupe → upsert
    categorise.py      rules, provider hints, keyword heuristics
    analytics.py       summaries, cash flow, budgets, recurring detection
  routers/           HTTP endpoints
frontend/            single-page client, no build step
tests/               88 tests
```

Adding another bank means writing one module in `providers/` — nothing above
that layer knows which bank it's talking to.

## Tests

```bash
pip install -r requirements-dev.txt
pytest
```

88 tests covering money arithmetic, CSV shapes, categorisation precedence,
sync idempotency, analytics maths, and the API's auth boundaries. Bank APIs are
mocked with `respx`; no test touches the network.

API docs, while the app is running: <http://localhost:8000/docs>.

## Limitations

- **Sync is manual.** Press *Sync now*, or `POST /api/connections/sync-all` from
  cron. There's no background scheduler.
- **No FX conversion**, by design (see above).
- **Read-only.** No payments, transfers or standing-order changes.
- **Single user per instance.**
- Open Banking gives you up to 730 days of history, depending on what the bank
  returns. CSV import is the way to go further back.
