# Deploying from a phone

Getting the app running on a free host, using nothing but a phone browser.
Roughly 15 minutes, most of it waiting for a build.

The app needs a server that stays on. This puts one on Render's free tier, with
a Postgres database, so your phone has something to talk to without you owning
a computer.

## Before you start

You need a GitHub account (the code is already there) and about 15 minutes.
No card is required for the free tier.

---

## 1. Create the services

1. Go to **[render.com](https://render.com)** and sign up with GitHub.
2. **New → Blueprint**.
3. Pick this repository, and set the branch to
   `claude/financial-manager-revolut-07q4wi`.
4. Render reads `render.yaml` and shows you a web service and a database.
   Press **Apply**.

That's the setup done. The first build takes about five minutes — it installs
Python packages, so it's slower than later deploys.

## 2. Get your sign-up code

The app is now on a public URL, so sign-up is locked behind a code. Otherwise
the first stranger who found the address could claim your instance.

In Render: your service → **Environment** → find `SIGNUP_TOKEN` and reveal it.
Copy the value.

## 3. Create your account

Your app's address is at the top of the Render service page — something like
`https://financial-manager-xxxx.onrender.com`.

Open it, and fill in the sign-up form. Paste the code into **Sign-up code**.
Registration then closes permanently — you are the only account.

**Do this promptly.** Until you register, the instance is unclaimed.

## 4. Point the phone app at it

Open the Android app and enter your Render URL, including the `https://`.

You can also skip the app: open the URL in Chrome and choose *Add to home
screen*. Since Render gives you HTTPS, the web app installs properly and looks
the same as the APK.

---

## What free actually means here

Two limits you should know before relying on this.

**The service sleeps.** After 15 minutes of no traffic, Render stops the
container. The next visit wakes it, which takes 30–60 seconds. After that it's
quick again. Nothing is lost — it's a delay, not data loss.

**The free database expires after 30 days.** This is Render's policy, not a
choice this app makes. When it goes, your transactions go with it.

Two ways to deal with that before day 30:

- **Upgrade the Render database** to their paid tier (a few dollars a month).
- **Move to a database that doesn't expire.** [Neon](https://neon.tech) has a
  free Postgres tier with no expiry. Create a database there, copy its
  connection string, and in Render set `DATABASE_URL` to that value. The app
  normalises the URL format for you, so paste it exactly as Neon gives it.

Either way, **export your transactions to CSV now and then** (Transactions →
Export CSV). It's the simplest insurance, and the import is deduplicated so
restoring is painless.

---

## Adding your keys later

All optional, all in Render → **Environment**. Add a value, save, and the
service restarts itself.

| Variable | What it turns on |
|---|---|
| `ANTHROPIC_API_KEY` | The AI assistant, merchant clean-up, monthly briefing |
| `GOCARDLESS_SECRET_ID` / `_KEY` | Connecting a personal Revolut account |

If you connect Revolut, add this to the allowed redirect URIs in your
GoCardless dashboard:

```
https://YOUR-APP.onrender.com/api/connections/gocardless/callback
```

The app works out its own public address from Render, so there's nothing to
configure on this side.

---

## Things worth thinking about

Your bank data is now on someone else's computer rather than yours. That's the
trade for not owning a computer, and it's a reasonable one, but it's worth
naming:

- Bank tokens are encrypted before they're stored, with a key Render generated
  and keeps in your environment. Anyone with access to your Render account can
  read that key.
- **Use a strong, unique password.** The sign-in page is on the public internet.
  Repeated wrong guesses get locked out for five minutes, which makes guessing
  impractical, but a reused password from another breach would sail past that.
- Every connection this app makes to your bank is read-only. Even in the worst
  case, nobody can move your money through it.
- `ENCRYPTION_KEY` and `SESSION_SECRET` are generated once and must not change.
  Changing the first makes stored bank connections unreadable; changing the
  second signs you out. Leave them alone.

If you later get a computer and want everything local again, the same code runs
with `./run.sh` and a SQLite file — no changes needed.
