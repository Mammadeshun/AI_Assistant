# Windows: start here

No terminal, no git, no commands to memorise. Four steps.

## 1. Install Python

Download it from **[python.org/downloads](https://www.python.org/downloads/)**
and run the installer.

> **On the very first screen, tick “Add python.exe to PATH”.**
> It's a small checkbox at the bottom and it is easy to miss. Without it,
> Windows won't find Python and you'll get *“python is not recognized”*.

If you already installed Python and got that message, run the installer again,
choose **Modify**, and make sure that box is ticked.

## 2. Download the app

Open this link — it downloads a ZIP:

<https://github.com/mammadeshun/ai_assistant/archive/refs/heads/claude/financial-manager-revolut-07q4wi.zip>

Then **right-click the downloaded file → Extract All**. Put it somewhere you'll
find again, like your Documents folder.

## 3. Start it

Open the extracted folder and **double-click `start-windows.bat`**.

A black window opens. The first time, it spends a few minutes installing things
— that's normal, and it only happens once. Then your browser opens on the app
by itself.

**When Windows asks whether to allow Python through the firewall, click Allow.**
Without that, your phone can't reach it.

Leave the black window open. Closing it stops the app.

Create your account on the page that opens. You're done on the laptop.

## 4. Put it on your phone

In the black window, find the line that looks like this:

```
From your phone:       http://192.168.43.100:8000   <- use this in the app
```

Your numbers will be different. Type **your** address into the Android app.

The phone and the laptop must be on the same network. Your phone's hotspot
counts — if the laptop is connected to it, you're already set.

---

## If it doesn't work

**“python is not recognized”** — the PATH checkbox in step 1 was missed. Re-run
the Python installer, choose Modify, tick it, then try again.

**The black window flashes and disappears** — it's reporting an error too fast
to read. Open the folder, click the address bar at the top, type `cmd` and press
Enter, then type `python start.py` and press Enter. Now the error stays on
screen. Send it to me.

**The browser opens but the page won't load** — give it a few seconds and
refresh. If it still fails, the black window will say why.

**The phone can't connect** — nearly always the firewall. Go to Windows Security
→ Firewall & network protection → Allow an app through firewall → find Python
and tick both Private and Public.

**Everything else** — copy the last ten lines from the black window and send
them to me. That text says exactly what went wrong.
