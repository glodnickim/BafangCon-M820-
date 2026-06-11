# Contributing

## Branch Strategy

```
master        — stable, release-ready
docs/*        — documentation changes only
feature/*     — new features (e.g. feature/can-ble-handshake)
fix/*         — bug fixes (e.g. fix/mtu-negotiation-race)
refactor/*    — refactoring without functional changes (e.g. refactor/ble-repository-split)
experimental/*— temporary branches for exploration
```

- `master` is protected. All changes must go through pull requests.
- `docs/*` branches are exempt from build requirements (markdown only).
- Feature branches should be short-lived (ideally < 1 week).

## Commit Naming

Use conventional commits:

```
feat: add CanBleHandshake state machine
fix: correct CRC-16-Kermit byte order
docs: add PHASE1_PLAN document
refactor: extract CryptoProvider interface
test: add FrameExtractor unit tests
chore: update gradle dependencies
```

Format:
```
<type>: <short description in present tense>

[optional body: explain what and why, not how]
```

- Maximum 72 characters for the subject line.
- Use present tense ("add" not "added" or "adds").
- Reference issue numbers when applicable (`fix: #42 handle null dynamic key`).

## Pull Request Workflow

1. Create branch from `master`:
   ```bash
   git checkout master
   git pull origin master
   git checkout -b feature/your-feature-name
   ```

2. Make changes, commit with conventional commit messages.

3. Rebase on latest master:
   ```bash
   git fetch origin
   git rebase origin/master
   ```

4. Push and create PR:
   ```bash
   git push origin feature/your-feature-name
   ```

5. PR title should match commit convention: `feat: add CanBleHandshake state machine`

6. PR description should include:
   - What the change does
   - Why it's needed
   - How it was tested
   - Any risks or considerations

7. Request review from at least one other contributor.

8. Merge to `master` after approval. Prefer **squash merge** for feature branches.

## Build Verification Before Merge

Before merging, ensure:

```bash
# Clean build
./gradlew clean assembleDebug

# If tests exist:
# ./gradlew test
```

- No compilation errors.
- No new warnings (if avoidable).
- All existing tests pass (when tests are added).
- Release build also works (verify with release keystore):
  ```bash
  .\build-app.ps1 -Mode release
  ```

## Code Style

- Follow the Kotlin Coding Conventions: https://kotlinlang.org/docs/coding-conventions.html
- 4-space indentation (no tabs).
- `private val` before `val` before `fun`.
- No wildcard imports (`import com.test.bafangcon.*`).
- Use `Log.d(TAG, "...")` for debug, `Log.i(TAG, "...")` for info, `Log.w(TAG, "...")` for warnings, `Log.e(TAG, "...")` for errors.
- Keep functions small (< 30 lines where possible).
- Prefer immutable data classes over mutable state.
- Use `StateFlow` for reactive state (not `LiveData`).
- Mark `TODO` comments with issue reference: `// TODO(#123): implement handshake retry`

## Documentation

- All new features must include documentation in `docs/`.
- Update `docs/PROJECT_STATUS.md` when adding/changing features.
- Update `docs/CAN_BLE_PHASE1_PLAN.md` when Phase 1 scope changes.
- Follow existing document format (markdown, sections, tables).

## Testing

Currently no test framework is set up. When adding tests:

- Unit tests in `app/src/test/java/` (JUnit 5 + Mockito).
- Use `RecordedCanSession.replay()` for CAN BLE tests without real hardware.
- Test edge cases: empty frames, malformed data, encryption failures.

## Versioning

- Version managed in `version.properties`:
  ```properties
  versionCode=42
  versionName=0.042
  ```
- `versionCode` increments by 1 for each release.
- `versionName` follows semantic versioning (or app-specific versioning).
- Update `CHANGELOG.md` with every release (keep "Unreleased" section up to date during development).
