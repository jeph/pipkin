# Pipkin

## Branches and worktrees

Several agents work on this repository at once, so make every change in its own git worktree instead of in the main checkout at `~/Developer/pipkin`. Create the worktree next to the main checkout on a new branch from `dev`, for example `git worktree add ../pipkin-<branch> -b <branch> dev`, and leave the main checkout on `dev`.

Documentation changes are the exception. Edit Markdown files such as `AGENTS.md` and `README.md` directly in the main checkout on `dev`; they don't need a worktree. The exception covers documentation only: changes to the build system or toolchain, such as the Gradle build files, `gradle/`, `config/detekt.yml`, or the host's `package.json`, `pnpm-lock.yaml`, and lint, type-check, and test configs, go in a worktree like any code change, even though they aren't application logic.

Development is trunk-based: `dev` is the trunk and the repository's default branch, and work reaches it in small, frequent pushes. Always pull `dev` into the worktree before building, with `git fetch origin && git rebase origin/dev`. Resolve any conflicts, and if changes other agents landed on `dev` break yours or don't work with them, fix that in your branch before you build. That way every build you test is the current `dev` plus your work, so changes on `dev` get integrated and tested together as they land.

Finished work goes directly to `dev`, and this project doesn't use pull requests. Once your work is finished and tested, rebase the branch onto `origin/dev` and run `git push origin HEAD:dev` from the worktree. Never push to `main` without the user's explicit approval; `main` only moves when the user asks for `dev` to be promoted to it. Never force push, to any branch, with `--force`, `--force-with-lease`, or a `+` refspec. If the push is rejected because `dev` moved, fetch, rebase onto `origin/dev` again, fix any conflicts or breakage, and push again. Worktree branches stay local: never push them to the repository under their own names.

Once the push has landed, remove the worktree and its branch so they don't pile up on this machine. Confirm the work is on `dev` with `git fetch origin && git merge-base --is-ancestor HEAD origin/dev`, then from the main checkout run `git worktree remove ../pipkin-<branch>` and `git branch -D <branch>`. Finally, bring the main checkout up to date with `git pull --ff-only origin dev`, so it matches `dev`.

## Testing on devices

Test changes on an Android emulator. Several agents work at once, so each worktree gets its own emulator, and agents never share one: installs, app data, and taps from one agent would otherwise overwrite or interrupt another's test. Don't install on, drive, or shut down an emulator that another worktree or project is using.

Before testing, create an emulator for the worktree, named after its branch, with the Pixel 9 hardware profile and a system image under `~/Library/Android/sdk/system-images`, for example `echo no | avdmanager create avd -n pipkin-<branch> -d pixel_9 -k "system-images;android-36;google_apis_playstore;arm64-v8a"` (the `echo no` answers its custom hardware profile prompt). Without `-d`, the emulator gets a 320×640 screen that Pipkin's UI and `adb` taps don't fit. Boot it on a free even port with `emulator -avd pipkin-<branch> -port <port> -memory 4096`, and pass `-s emulator-<port>` to every `adb` command so it can't reach another agent's emulator or the phone. When you remove the worktree, shut the emulator down with `adb -s emulator-<port> emu kill` and delete it with `avdmanager delete avd -n pipkin-<branch>`.

To chat with a model on the emulator, run `scripts/emulator-llm.sh emulator-<port>` once Pipkin's setup has installed the agent. It signs Pipkin in to the shared codex-lb provider, with the API key stored in the Mac's Keychain under `pipkin-codex-lb`, and makes GPT-6-Astra the default model. Never print the key or copy it anywhere else.

The user's phone is sometimes reachable over `adb`, but it's their own phone, not a test device. Ask the user before you install a build on it, run commands on it, or test on it, and do only what they agreed to. Never change settings on the phone, whether in Android, Termux, Pipkin, or any other app, without asking first.

## Code you touch

Leave code you change better than you found it, and limit the cleanup to that code: don't refactor whole files or unrelated parts of the codebase. Where a change touches a function or block, flatten its nesting where you can, using early returns and guards and reordering conditions so the common path reads straight down, and simplify what's there. Some nesting is unavoidable; keep it when flattening would make the code harder to follow. Rename variables, properties, and functions in that code whose names no longer match what they hold or do, or could say it more precisely.

## Checks

A task isn't done until `./gradlew check` passes. It runs ktlint, detekt, Android lint, the unit tests, and the host's type check, lint, and tests, and every release build runs it first, so a failure on `dev` blocks the next release. Fix any failure it reports, including ones in code you didn't change, rather than leaving it for later. Fix the code instead of silencing a check: don't add `@Suppress`, lint or detekt baselines, ignore comments, or configuration that turns a rule off.

## Text the user sees

Pipkin is set up for translation, so every text the app itself shows comes from a string resource in `app/src/main/res/values/strings_*.xml`: labels, errors, notices, notifications, and snackbars alike. Text from Pi or the model is shown as Pi sends it. In Compose, use `stringResource` or `pluralStringResource`; code without a `Context` uses `AppStrings`. An exception whose message reaches the UI, like `HostFailure` or `RpcError`, resolves its message when it's read, so it's never stored in English. Give a string with placeholders a comment saying what each one holds. Format numbers, times, and dates with the locale (`NumberFormat`, `DateFormat`, `DateUtils`), not fixed patterns or English suffixes.

The Termux host writes its messages in English, and each one the app may show also carries an `i18n` object, `{ key, args }`, beside the English: `i18n` on a failure, `<field>I18n` beside a text field such as `error` or `line`. `app/src/main/kotlin/io/jeph/pipkin/host/HostText.kt` maps each key to a resource in `strings_host.xml` and falls back to the English for a key it doesn't know. When the host gets a new message, add its key, the mapping, and the resource together.

Debug builds include pseudo-locales. Set Pipkin's language to English (XA) to find text that skips resources, which stays plain English while everything else turns accented, or to Arabic (XB) to check right-to-left layouts, for example with `adb -s emulator-<port> shell cmd locale set-app-locales io.jeph.pipkin --locales en-XA`.

## Commits

Write commit messages in the [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) format: `<type>(<optional scope>): <description>`, for example `feat(composer): add a context window meter next to the send button`. Use `feat` for new behavior, `fix` for bug fixes, `docs` for documentation-only changes, and `refactor`, `perf`, `test`, `build`, `ci`, or `chore` where they fit. Mark breaking changes with `!` after the type or scope and a `BREAKING CHANGE:` footer.

Commits are signed through 1Password, so commit normally first. If 1Password doesn't allow the signature, for example because the request times out or the agent returns an error, commit again without signing using `git -c commit.gpgsign=false commit`. Don't change the repository's or your global Git signing settings to get around it.

## Third-party licenses

Before adding a dependency, whether a Gradle dependency in `app/build.gradle.kts`, a host package in `host/package.json`, or a Pi runtime package in `app/src/main/assets/pi/package.json`, check its license. Don't add copyleft code (GPL, LGPL, AGPL) without asking first, since it would change how Pipkin can be distributed.

When you copy or adapt code, icons, or other assets from another project, keep a header in the copied file naming the source, version, and license, as the Lucide icons in `app/src/main/res/drawable/ic_lucide_*.xml` do.

## Vendored pi-web-access

The `pipkin-web-access` extension in `host/src/pi-extensions/pipkin-web-access/` is a copy of the upstream pi-web-access package with a few Pipkin changes. Before updating it to a new upstream release or editing it, read `UPSTREAM.md` in that folder: it lists every difference from upstream and the steps for folding in a release.
