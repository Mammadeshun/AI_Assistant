# Windows: start here

## The easy way — one file, nothing to install

**[Download FinancialManager.exe](https://github.com/Mammadeshun/AI_Assistant/releases/download/windows-build/FinancialManager.exe)**  (30 MB)

1. Put it somewhere sensible first — a folder like `Documents\Finances`. The app
   creates its database next to itself, so where you put it is where your data
   lives.
2. **Double-click it.**
3. Windows will warn that the publisher is unknown. That is because the file
   isn't code-signed, not because anything is wrong with it. Click
   **More info** then **Run anyway**.
4. A black window opens and your browser follows a few seconds later.
5. If Windows asks about the firewall, click **Allow** — without it your phone
   can't reach the app.

Create your account on the page that opens. That is the whole installation:
Python is bundled inside the .exe, so there is nothing else to install.

**Keep the black window open** while you use the app. Closing it stops the app.
To start again another day, double-click the same file.

## Putting it on your phone

In the black window, find:

```
From your phone:   http://192.168.43.100:8000
                   ^ type this into the Android app
```

Your numbers will differ — use what your window shows. The phone and the laptop
must be on the same network; your phone's hotspot counts, if the laptop is
connected to it.

## Turning on the assistant

The Assistant tab stays switched off until you give it a key. Create one at
<https://console.anthropic.com>, then:

1. Open the folder with `FinancialManager.exe` in it.
2. Open the `.env` file there with Notepad (right-click → Open with → Notepad).
3. Find the `ANTHROPIC_API_KEY=` line and paste your key after the `=`, with no
   spaces and no quotes.
4. Save, close the black window, and start the app again.

That key is tied to your billing — keep it in that file and nowhere else. Don't
paste it into a chat, a screenshot, or a message to anyone, including me.

## Does my laptop have to be on?

While the app runs from this .exe, yes: the laptop is the server, and your phone
is only a window onto it. Close the black window or shut the lid and the app on
your phone has nothing to talk to.

If you want it available whether or not the laptop is on, run the server on a
free cloud host instead — see [DEPLOY.md](DEPLOY.md). It takes about ten minutes
from a phone browser, and afterwards the laptop is optional.

## Where your data lives

Next to the .exe, in two places:

- `data\finance.db` — your accounts and transactions
- `.env` — your settings and API keys (open it in Notepad to add them)

Back it up by copying that folder. Move the folder to another machine and it
carries on where it left off.

## If something goes wrong

**The window flashes and disappears** — the app is reporting an error too
quickly to read. It shouldn't do this (it waits for a keypress on failure), but
if it does, open Command Prompt in that folder and run `FinancialManager.exe`
from there so the message stays on screen.

**"Windows protected your PC"** — SmartScreen. **More info** then **Run anyway**.
It appears because the file isn't code-signed; signing costs money every year.

**Antivirus quarantines it** — PyInstaller executables are sometimes flagged by
heuristics, because malware uses PyInstaller too. If yours objects, allow it, or
use the from-source method below.

**The browser doesn't open** — type `http://localhost:8000` in yourself.

**The phone can't connect** — nearly always the firewall. Windows Security →
Firewall & network protection → Allow an app through firewall → find
FinancialManager and tick both Private and Public.

## The other way — running from source

If you would rather not use a prebuilt executable, or you want to change the
code:

1. Install Python from [python.org](https://www.python.org/downloads/), ticking
   **Add python.exe to PATH** on the first screen.
2. Download the repository as a ZIP and extract it.
3. Double-click `start-windows.bat`.

That builds everything locally instead of using the packaged build. Same app.
