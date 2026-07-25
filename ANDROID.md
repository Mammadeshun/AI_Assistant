# The Android app

A real Android app, not a web page in a wrapper. Your transactions live on the
phone in its own database, and there is no server, no account and no computer
involved.

**[Download FinancialManager.apk](https://github.com/Mammadeshun/AI_Assistant/releases/download/android-build/FinancialManager.apk)**

1. Open that link **on your phone**.
2. Open the file once it downloads. Android will ask whether to allow installing
   from this source — that is expected for an app that isn't from the Play Store.
   Allow it, then install.
3. Open the app. It will ask for a statement.

## Getting your money into it

In the Revolut app: **⚙️ → Statement → Download**, and save it to your phone.
Either format works — the PDF it offers first, or the CSV behind the format
choice.

Then in this app: **Import a statement**, and pick the file. A few seconds later
your history is there, categorised.

Importing the same statement twice is safe. Every row is identified by its
content and its position among identical rows that day, so a second import adds
nothing — and two identical coffees on one afternoon still count as two.

A note you may see afterwards: *"N dates taken from the row above"*. Some rows in
Revolut's PDF print their date as `########`, a column too narrow to fit, baked
into the file. Those rows keep their merchant and amount and inherit the date
above them. It is a guess, and the app says so rather than pretending otherwise.

## Does it need my laptop?

No. Nothing about this app talks to a computer. It reads the statement on the
phone, stores it on the phone, and works with the phone offline.

## The assistant

Off until you give it a key, and everything else works without it.

**Settings → Assistant**, paste an Anthropic API key from
<https://console.anthropic.com>, save. Then the **Ask** tab answers questions
about your own figures: "how much did I spend on groceries this month", "what
changed since last month".

The key is kept in Android's encrypted storage, which is backed by the phone's
hardware keystore. It is sent to Anthropic and nowhere else. Treat it like a
bank card: it is tied to your billing, and it is not something to paste into a
chat, a screenshot or a message to anyone.

What gets sent when you ask a question: this month's and last month's totals, the
category breakdown, and your largest merchants. Not your full history, not dates,
not counterparties, not anything identifying. Ask nothing and nothing is sent.

## Where your data is

In the app's own private storage, which no other app can read. Uninstalling the
app deletes it, so keep the statement files if you want to be able to rebuild.

**Settings → Delete everything** clears it out on demand.

## Building it yourself

```bash
cd android-native
gradle :app:testDebugUnitTest     # the logic, including the statement parser
gradle :app:assembleRelease       # → app/build/outputs/apk/release/
```

Needs a JDK 17+ and an Android SDK with platform 35. The release APK is signed
with Android's standard debug key — enough to install on your own device, not
enough to publish anywhere.

### Checking the parser against a real statement

The parser is covered by unit tests using synthetic rows, plus one test that
runs against a real statement's extracted text and checks the totals. Real bank
history isn't in this repository, so that test skips unless you point it at a
file:

```bash
gradle :app:testDebugUnitTest -PstatementLines=/path/to/extracted-lines.txt
```

## What it doesn't do yet

- **No live bank connection.** Open Banking needs a web callback to complete
  authorisation, which an app with no server has nowhere to receive. Statements
  are the way in for now; the web version in this repo does live sync if you
  want that.
- **Budgets and recurring-payment detection** are in the web version and not
  yet here.
