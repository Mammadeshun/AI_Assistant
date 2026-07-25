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

## What it shows you

**Home** leads with what is safe to spend — the balance minus what is still due
to leave this month — and under it, **Coming up**: the payments about to be
taken, soonest first. That is the part a statement cannot tell you. A balance
that looks fine on the 3rd is a different number once you can see the €500 of
rent leaving on the 5th.

**Plan** is where debts, instalment plans, subscriptions and bills are recorded,
along with the cash in your pocket that no bank export knows about, and any
monthly limits you want to set on a category.

There is a **home-screen widget**: the same figure, the month running down as a
row of blocks, and the next payment due. Long-press the home screen → Widgets →
Financial Manager.

## The assistant

Off until you give it a key, and everything else works without it.

**Settings → Assistant**, paste an Anthropic or Gemini API key, save. The button
that follows you around the app then opens it — drag it wherever suits your
thumb; it stays there.

It answers questions about your own figures ("how much did I spend on groceries
this month", "what changed since last month"), and it changes things when you
ask: add a debt, set a budget, say how much cash you're carrying, refile a
merchant. It does not ask permission first — every change appears under **Just
changed** on the home screen with an Undo beside it.

You can also send it a photo. Point it at a Klarna, Scalapay or loan screen and
the plan is read off it and saved: amounts, dates, how many payments are left.
The same Undo applies if it misreads a line.

The key is kept in Android's encrypted storage, which is backed by the phone's
hardware keystore. It is sent to Anthropic and nowhere else. Treat it like a
bank card: it is tied to your billing, and it is not something to paste into a
chat, a screenshot or a message to anyone.

What gets sent when you ask a question: this month's and last month's totals, the
category breakdown, your largest merchants, and what you have recorded as owing.
Not your full history, not dates, not counterparties, not anything identifying.
A photo is sent only when you attach one. Ask nothing and nothing is sent.

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

### The design is screenshot-tested

The screens are rendered to PNG on the JVM by Paparazzi and compared against
`app/src/test/snapshots/`. There is no emulator on the machine this is built on,
and a layout that overflows or a colour that vanishes against its own background
is not something an ordinary test notices — the first run of these caught a
heading rendering black on a dark background.

```bash
gradle :app:recordPaparazziDebug   # after an intentional design change
gradle :app:verifyPaparazziDebug   # what the test suite runs
```

Changing the look on purpose means re-recording; a change that alters a screen
without meaning to fails the build.

The activity itself is launched under Robolectric too, which covers the parts a
screenshot cannot: the tab bar, the floating assistant button, and the overlay it
opens and closes.

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
