# Speak to Survive - Android app (Phase 2: password + Bluetooth relay)

Hands-free emergency alerts. Triggers:
- Secret voice phrase (offline voice recognition, nothing leaves the phone)
- Power button pressed 5 times quickly (changeable 3-8 in Settings)
- "Hold to send SOS now" button

When triggered the app:
1. SMS to every saved contact with a Google Maps location link
2. Calls the first contact automatically (can be switched off)
3. Fetches a fresh GPS fix and sends location updates every minute
4. Retries any SMS that did not go through (weak or no signal)
5. "I'M SAFE" stops everything and tells your contacts you are safe

New in this version:
- **Stop password**: needed to stop an SOS, switch protection off, or open Contacts/Settings while protection is on.
  The "I'M SAFE" button in the notification also asks for the password. The password is stored only as a salted hash.
  3 wrong tries lock the box for 30 seconds (then longer).
- **Bluetooth relay (Node A -> Node B -> Node C)**: if A's SMS cannot be sent (no signal), A broadcasts a tiny Bluetooth
  alert that contains the location and the first 2 emergency numbers. Any nearby phone (B) running the app hears it,
  passes it on to further phones (3 hops), and - if it has signal - sends the SMS to the contact (C) on A's behalf.

Not in this version (planned next): Firebase live-tracking page.

## Get the APK without installing anything (about 15 minutes)

1. Create a free account at https://github.com
2. Click **+** (top right) > **New repository**. Name it `speak-to-survive`. Choose **Public**. Click **Create repository**.
3. Click **uploading an existing file**.
4. Unzip this project on your computer. Open the unzipped folder and drag EVERYTHING inside it
   (the `app` folder, `.github` folder, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `.gitignore`, `README.md`)
   into the GitHub page. Wait until all files finish uploading, then click **Commit changes**.
   - If the hidden `.github` folder did not upload (it is hidden on Mac/Windows):
     on GitHub click **Add file > Create new file**, type the name `.github/workflows/build.yml`
     (typing the slashes creates the folders), paste the contents of that file, and commit.
5. Click the **Actions** tab. If asked, click the green button to enable workflows.
   The build starts by itself. If it does not, click **Build APK** on the left, then **Run workflow**.
6. Wait about 5-10 minutes for a green tick.
7. Click the finished run, scroll to **Artifacts**, and download **SpeakToSurvive-APK** (a zip). Unzip it to get `app-debug.apk`.
8. Send `app-debug.apk` to your phone (WhatsApp, Drive, USB cable).
9. On the phone, open the APK. Allow "Install unknown apps" when asked. Ignore the Play Protect warning (tap "Install anyway").

If the build shows a red cross, open the failed run, copy the error text and send it to Claude.

## First run on the phone

1. Contacts tab: add 1-3 trusted contacts (friends or family. Never use 112 for testing).
2. Settings tab: enter your name and choose a secret phrase (2-4 common English words).
3. Settings > "Keep it working in the background": allow Display over other apps and Unrestricted battery.
   On Xiaomi/Oppo/Vivo/Realme also turn on Autostart for the app in the phone's own settings.
4. Home tab: tap the big button, allow all permissions, and wait for "Listening".
5. Tap "Send test alert". Your contacts get an SMS marked TEST ALERT. No call is made.
6. Then try the real triggers with a friend as the contact (turn Auto-call off in Settings if you do not want a call).

## Testing the Bluetooth relay (needs 3 phones)

- Phone A (victim): install the app, add Phone C's number as contact, switch protection on, Bluetooth ON.
  Turn on Airplane mode, then switch Bluetooth back on (Airplane mode turns it off; Bluetooth can stay on with Airplane mode).
- Phone B (helper): install the app, switch protection on (Help others is on by default), Bluetooth ON, with mobile signal.
  Keep it within a few metres of A for the first test.
- Phone C: any phone with the number you saved on A. It does not need the app.
- On A tap "Send test alert". A's SMS fails, A starts the Bluetooth broadcast, B shows a "Test alert nearby" notification
  and sends an SMS marked TEST ALERT to C with the location link. Home shows "Nearby help" on B.
- For a demo without Airplane mode, switch on "Always broadcast (demo mode)" in Settings on A.
- Each helper phone sends the relay SMS from its own SIM. Do not use real emergency numbers while testing.

## Notes

- Voice model: the build downloads a small offline Indian-English model automatically.
  Tamil is not supported by this engine; use English words.
- After restarting the phone, open the app and tap the big button again (Android does not allow
  microphone services to start by themselves after a reboot).
- The power-button trigger counts screen on/off changes. Turn off the phone's own
  Emergency SOS / camera power-button shortcuts so they do not clash.
- The password protects the app screens and the notification button. Android itself still lets anyone switch the phone
  off or force-stop the app from the system settings, and the app cannot block that.
- Bluetooth packets carry only: location, 2 phone numbers (up to 12 digits incl. country code; 10-digit Indian numbers
  get +91), a 4-letter name tag and a counter. A helper phone limits itself to 6 relayed alerts per hour.
- Android may delay or limit calls started when the screen is off. Allowing "Display over other apps" helps.
