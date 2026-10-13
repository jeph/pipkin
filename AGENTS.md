# Pipkin

## Branches and worktrees

Several agents work on this repository at once, so make every change in its own git worktree instead of in the main checkout at `~/Developer/pipkin`. Create the worktree next to the main checkout on a new branch from `main`, for example `git worktree add ../pipkin-<branch> -b <branch> main`, and leave the main checkout on `main`.

Documentation changes are the exception. Edit Markdown files such as `AGENTS.md` and `README.md` directly in the main checkout on `main`; they don't need a worktree. The exception covers documentation only: changes to the build system or toolchain, such as the Gradle build files, `gradle/`, `config/detekt.yml`, or the host's `package.json`, `pnpm-lock.yaml`, and lint, type-check, and test configs, go in a worktree like any code change, even though they aren't application logic.

Development is trunk-based: `main` is the trunk, and work reaches it in small, frequent pushes. Always pull `main` into the worktree before building, with `git fetch origin && git rebase origin/main`. Resolve any conflicts, and if changes other agents landed on `main` break yours or don't work with them, fix that in your branch before you build. That way every build you test is the current `main` plus your work, so changes on `main` get integrated and tested together as they land.

Finished work goes directly to `main`, and this project doesn't use pull requests, but only push once the user has explicitly told you to; until then, commit locally and ask. From a worktree, rebase the branch onto `origin/main` and run `git push origin HEAD:main`. Never force push, to any branch, with `--force`, `--force-with-lease`, or a `+` refspec. If the push is rejected because `main` moved, fetch, rebase onto `origin/main` again, fix any conflicts or breakage, and push again. Worktree branches stay local: never push them to the repository under their own names.

Once the push has landed, remove the worktree and its branch so they don't pile up on this machine. Confirm the work is on `main` with `git fetch origin && git merge-base --is-ancestor HEAD origin/main`, then from the main checkout run `git worktree remove ../pipkin-<branch>` and `git branch -D <branch>`. Finally, bring the main checkout up to date with `git pull --ff-only origin main`, so it matches `main`.

## Testing on devices

Test changes on an Android emulator. Several agents work at once, so each worktree gets its own emulator, and agents never share one: installs, app data, and taps from one agent would otherwise overwrite or interrupt another's test. Don't install on, drive, or shut down an emulator that another worktree or project is using.

Before testing, create an emulator for the worktree, named after its branch, from a system image under `~/Library/Android/sdk/system-images`, for example `echo no | avdmanager create avd -n pipkin-<branch> -k "system-images;android-36;google_apis_playstore;arm64-v8a"` (the `echo no` answers its custom hardware profile prompt). Boot it on a free even port with `emulator -avd pipkin-<branch> -port <port>`, and pass `-s emulator-<port>` to every `adb` command so it can't reach another agent's emulator or the phone. When you remove the worktree, shut the emulator down with `adb -s emulator-<port> emu kill` and delete it with `avdmanager delete avd -n pipkin-<branch>`.

The user's phone is sometimes reachable over `adb`, but it's their own phone, not a test device. Ask the user before you install a build on it, run commands on it, or test on it, and do only what they agreed to. Never change settings on the phone, whether in Android, Termux, Pipkin, or any other app, without asking first.

## Code you touch

Leave code you change better than you found it, and limit the cleanup to that code: don't refactor whole files or unrelated parts of the codebase. Where a change touches a function or block, flatten its nesting where you can, using early returns and guards and reordering conditions so the common path reads straight down, and simplify what's there. Some nesting is unavoidable; keep it when flattening would make the code harder to follow. Rename variables, properties, and functions in that code whose names no longer match what they hold or do, or could say it more precisely.

## Commits

Write commit messages in the [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) format: `<type>(<optional scope>): <description>`, for example `feat(composer): add a context window meter next to the send button`. Use `feat` for new behavior, `fix` for bug fixes, `docs` for documentation-only changes, and `refactor`, `perf`, `test`, `build`, `ci`, or `chore` where they fit. Mark breaking changes with `!` after the type or scope and a `BREAKING CHANGE:` footer.

## Third-party licenses

Before adding a dependency, whether a Gradle dependency in `app/build.gradle.kts`, a host package in `host/package.json`, or a Pi runtime package in `app/src/main/assets/pi/package.json`, check its license. Don't add copyleft code (GPL, LGPL, AGPL) without asking first, since it would change how Pipkin can be distributed.

When you copy or adapt code, icons, or other assets from another project, keep a header in the copied file naming the source, version, and license, as the Lucide icons in `app/src/main/res/drawable/ic_lucide_*.xml` do.
