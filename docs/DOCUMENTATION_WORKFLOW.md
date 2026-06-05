# Documentation Workflow

This file explains how to document the app while coding, without slowing down
development.

## Rule

Document user-visible behavior, risky protocol findings and release changes.
Do not document every small code edit.

## During Development

When you add or fix something, write a short note in one of these places:

- `CHANGELOG.md` - if the user will notice it in a release.
- `docs/*.md` - if it explains protocol, testing or developer workflow.
- Code comments - only when the code is hard to understand without context.

Good note:

```md
Added ride logging to CSV. The user must select a folder before logging starts.
The CSV uses A3 telemetry bytes 160..207 and appends last known A4 battery data.
```

Too much detail:

```md
Changed variable name from x to y.
Moved one line inside if.
```

## Before Every Release

1. Move important notes from `Unreleased` to a version section in `CHANGELOG.md`.
2. Create release notes using `docs/RELEASE_NOTES_TEMPLATE.md`.
3. Run `docs/RELEASE_CHECKLIST.md`.
4. Build release APK with:

```powershell
.\build-app.ps1 -Mode release
```

## Version Rule

The build script copies the APK first and then bumps `version.properties`.

Example:

- before build: `versionName=0.038`
- generated APK: `Bafang FT M820 v0.038.apk`
- after build: `versionName=0.039`

The release notes and changelog must use the generated APK version.

## What To Record In Changelog

Use these categories:

- `Added` - new features.
- `Changed` - behavior changed.
- `Fixed` - bug fixes.
- `Known Issues` - known limitations.

Keep each bullet short and user-focused.

## What To Record In Protocol Docs

Use protocol docs for findings like:

- command IDs
- byte offsets
- scaling, for example `speedRaw * 0.01`
- confirmed devices
- frames that do not work
- assumptions that need testing

Always include raw offsets or frame names when possible.
