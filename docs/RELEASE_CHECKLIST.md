# Release Checklist

Use this before publishing a new APK.

## 1. Version

- [ ] Check current version:

```powershell
Get-Content .\BafangCon\version.properties
```

- [ ] Remember: this is the version that the next APK will use.
- [ ] After build, the script bumps this file to the next version.

## 2. Functional Test

- [ ] App opens on the test phone.
- [ ] Bluetooth permissions are granted.
- [ ] App scans for the display.
- [ ] App connects to the display.
- [ ] Authentication succeeds.
- [ ] Main screen loads assist/controller data.
- [ ] System tab shows A3 controller values.
- [ ] Every new `Log` start opens Android folder picker.
- [ ] After selecting folder, CSV file is created there.
- [ ] CSV contains changing speed/current/voltage/power values during ride or bench test.
- [ ] `Stop` closes the CSV file.
- [ ] Disconnect stops logging safely.

## 3. Build

Always build release:

```powershell
.\build-app.ps1 -Mode release
```

Do not publish debug APK unless it was explicitly requested.

## 4. Artifact

- [ ] Confirm copied APK exists:

```text
BafangCon\Bafang FT M820 vX.XXX.apk
```

- [ ] Install the copied APK on the test phone.
- [ ] Launch it once after install.
- [ ] Confirm the version in the filename is the version being published.

## 5. Changelog

- [ ] Move `Unreleased` items to `vX.XXX - YYYY-MM-DD`.
- [ ] Add `Added`, `Changed`, `Fixed`, `Known Issues` sections as needed.
- [ ] Leave `Unreleased` empty for the next version.

## 6. Release Notes

- [ ] Copy `docs/RELEASE_NOTES_TEMPLATE.md`.
- [ ] Replace `vX.XXX` with the APK version.
- [ ] Keep the public notes short and user-readable.
- [ ] Mention known limitations if they affect testing.

## 7. Publish

- [ ] Upload APK.
- [ ] Upload or paste release notes.
- [ ] Keep a local copy of the APK.
- [ ] If users report issues, add them to `Known Issues` or `Unreleased`.
