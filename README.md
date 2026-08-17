# TACTICOM Android

A native Android port of the Termux-based TACTICOM server. It's a
background service, not a full app UI: it hosts the exact same web
page/lobby/session/push-to-talk experience your family already uses in
their browsers, and it rings this phone natively (vibration + notification
+ looping ringtone) when someone taps "Ring the Host" -- all without
needing Termux installed at all.

**What this is NOT**: it doesn't put the walkie-talkie screen (session
list, push-to-talk button) inside the app itself. You keep using Chrome
(or any browser) to actually talk, exactly like today -- the app's own
screen is just a status/settings panel. This was a deliberate choice to
avoid a known flaky area (microphone capture inside an Android WebView)
that adds real risk for no functional benefit here. If you actually
wanted the full UI embedded in the app, say so and we can revisit it.

## Important, upfront

I could not compile or run this project myself -- my sandbox has no
Android SDK and no access to Google's Maven repository. Every file here
was written carefully and cross-checked by hand (XML validated, every
class/resource reference checked against what actually exists), but a
real Gradle build is the only thing that can catch 100% of possible
mistakes. **The first build may surface an error or two.** If it does:
copy the failing step's log from the GitHub Actions output and send it to
me -- that's a fast, precise fix, much faster than guessing blind.

## 1. Get this onto GitHub

1. Create a new **empty** repository on GitHub (no README/license/gitignore
   -- keep it empty so there's nothing to merge).
2. Unzip this project locally (or directly on the machine you'll push
   from), then from inside the project folder:

```bash
git init
git add .
git commit -m "Initial TACTICOM Android app"
git branch -M main
git remote add origin https://github.com/<your-username>/<your-repo>.git
git push -u origin main
```

## 2. Let GitHub Actions build the APK

Pushing to `main` automatically triggers `.github/workflows/build-apk.yml`,
which builds a **debug APK** in the cloud -- no signing keystore or
secrets needed for this step, since Android's own auto-generated debug
key handles that.

1. On GitHub, open your repo's **Actions** tab.
2. Click the running (or most recent) "Build APK" workflow.
3. Once it finishes (green check), scroll to **Artifacts** and download
   `tacticom-debug-apk` -- it's a zip containing `app-debug.apk`.

If it's red instead of green, click into the failed step and send me that
log output.

## 3. Install it on your phone

Debug APKs aren't on the Play Store, so:

1. Transfer `app-debug.apk` to your phone (download it directly in the
   phone's browser from the Actions artifact, or AirDrop/USB/etc.).
2. Tap the file. Android will ask to allow installs from that source
   ("install unknown apps") -- allow it, then install.
3. Open the app once. It will:
   - Ask for notification permission (needed even to show the
     "server running" notification) -- allow it.
   - Automatically start the background service.
4. Tap **"Exempt from battery optimization"** on the app's screen and
   confirm on the system dialog that appears. This is the single most
   important step for reliably surviving hours in the background --
   without it, some phones (Samsung/Xiaomi/OnePlus especially) will
   still kill it eventually despite everything else in this app.
5. Optionally tap **"Choose ring sound"** to pick any audio file instead
   of your phone's default ringtone.

The app's own screen will show something like:
```
Running
https://192.168.1.42:8443
```
That address is what everyone else in the house opens in their browser --
identical to the Termux version. You'll hit the same "connection not
private" warning the first time (self-signed certificate) -- that's
expected, same as before; tap through it (Advanced -> Proceed).

## 4. Test it

- From another device on the same Wi-Fi, open that address, set a
  profile name, start or join a session, talk. This part is byte-for-byte
  the same client you already tested and confirmed working over Termux.
- From a third device (or the same one), tap **Ring the Host** -- your
  phone should vibrate, show a notification, and start playing a ringtone
  on a loop. Tap **STOP RINGING** on the notification to confirm it stops
  cleanly.
- Turn the phone's screen off, wait a minute, ring it again from another
  device -- confirming it still works with the screen off is the actual
  point of all this background-service plumbing.
- Turn Wi-Fi off and back on (or move to a different network) and confirm
  the address shown in the app updates to the new IP within a couple
  seconds.

## 5. Auto-start on reboot

`BootReceiver` restarts the service automatically after the phone
reboots. Some manufacturers (Samsung, Xiaomi, Huawei, OnePlus, and a few
others) additionally require you to manually allow "autostart" for the
app in their own battery/app-management settings beyond what Android
itself exposes -- if it doesn't come back after a reboot, check your
phone model's autostart settings (searching "[your phone model] autostart
permission" usually finds the exact menu).

## Known limitations / things to improve once this is working

- **Debug build only, for now.** `assembleDebug` needs zero secrets,
  which is why it's the first target -- but debug builds aren't meant for
  long-term daily use (no code shrinking, debug-only cert). Once this is
  confirmed working end to end, the next step is a signed **release**
  build, which needs a real keystore generated once and stored as GitHub
  Actions secrets. Ask and I'll set that up.
- **Shared TLS certificate.** Every install of this APK bundles the same
  self-signed certificate (`app/src/main/assets/tacticom_cert.p12`) --
  fine for a trusted home LAN (same trust model as the Termux version's
  auto-generated cert), but technically means the private key isn't
  unique per device. A future improvement is generating a fresh
  certificate on first launch instead (using Bouncy Castle), if that
  matters to you.
- **GitHub Actions artifacts expire** (90 days by default). Fine for
  active development; if you want a permanent download link, the next
  step is wiring up a GitHub **Release** on tagged versions instead.

## Project layout

```
tacticom-android/
  app/
    src/main/
      AndroidManifest.xml
      java/com/familyintercom/tacticom/
        MainActivity.kt        -- status/settings screen
        TacticomService.kt     -- foreground service, network-change handling
        TacticomServer.kt      -- the actual HTTP+WebSocket server (Kotlin port of tacticom_server.py)
        RingController.kt      -- native vibration/notification/ringtone
        BootReceiver.kt        -- restarts the service after a reboot
      res/                     -- layout, icon, theme
      assets/
        web/index.html         -- the exact same client page you already tested
        tacticom_cert.p12      -- bundled self-signed TLS cert for HTTPS
  .github/workflows/build-apk.yml  -- builds the APK on every push
```
