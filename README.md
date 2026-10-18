# Pipkin

[![License](https://img.shields.io/github/license/jeph/pipkin)](LICENSE)

**Pipkin** is a native Android app for agentic coding. Tell it what to build or fix, and it reads your
code, runs commands, edits files, and checks its own work, all on your phone. It's built on the
[Pi](https://pi.dev) agent harness and runs in [Termux](https://termux.dev), so there's no laptop or
cloud server doing the work somewhere else.

> [!IMPORTANT]
> Pipkin is not affiliated with Termux, Pi, Anthropic, or OpenAI. The agent runs real commands in
> Termux and can read and change your phone's shared storage once you grant it access, so review
> what it does the same way you would on a computer.

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installation](#installation)
- [Usage](#usage)
- [Troubleshooting](#troubleshooting)
- [Development](#development)
- [Contributing](#contributing)
- [License](#license)

## Features

Most agents you use from a phone are remotes for a computer somewhere else. Pipkin runs the agent,
your code, and every command it runs on the phone itself:

- **No computer required.** The agent, your files, git, and terminals all live in Termux on your
  phone. Install compilers, runtimes, and databases with `pkg`, or run Debian or Ubuntu with
  `proot-distro`, no root required.
- **Built on Pi.** Pipkin is compatible with Pi's ecosystem of skills, tools, prompt templates, and
  extensions. Install extensions the usual Pi way and Pipkin picks them up.
- **Native extension UI.** Extensions that use Pi's RPC UI protocol render with native controls:
  choices, confirmations, and text fields appear as cards above the composer, and status lines,
  notices, and widgets each get a home.
- **Android native.** Pipkin is written in Jetpack Compose with no web view, so scrolling, typing, and
  streaming replies stay smooth in long chats. On foldables and tablets, split panes put chats,
  files, diffs, and terminals side by side.
- **A real workbench.** Review changes in the built-in git diff viewer, browse and read files with
  tree-sitter syntax highlighting for more than twenty languages, look back through commits, and
  open terminals without leaving the app.
- **Your model, your plan.** Sign in with a Claude Pro or Max subscription or an OpenAI Codex
  subscription, add a Gemini, Z.ai Coding Plan or Kimi Code API key, or add any OpenAI-compatible
  API, including hosted gateways and servers you run yourself. Add several and switch per chat.
- **Runs in the background.** Lock your phone or switch apps; a notification tells you when the chat
  is done or needs your answer.
- **Guided setup.** A step-by-step guide sets up Termux and installs Pi for you, explaining each
  permission it asks for.
- **Bundled extensions.** Pipkin ships web search and page fetching (a vendored copy of
  [pi-web-access](https://github.com/nicobailon/pi-web-access)) and a to-do list widget.
- **Backups.** Save your settings, sign-ins, models, and extensions in one tap and restore them
  later.

## How it works

| Part | Location | Role |
| --- | --- | --- |
| Android app | `app/` | The Kotlin and Jetpack Compose UI: onboarding, chats, files, git, terminals, and settings. |
| Host | `host/` | A TypeScript program, bundled into the APK, that the app runs in Termux to drive Pi over its RPC protocol and serve files, git, and terminals. |
| Pi runtime | `app/src/main/assets/pi/` | The pinned Pi version and runtime packages that setup installs in Termux with `npm ci`. |
| Bundled extensions | `host/src/pi-extensions/` | The Pi extensions Pipkin ships, bundled into the APK and installed alongside Pi. |
| Website | `site/` | The Astro marketing site. |

Pipkin uses Termux only through its public interfaces: `RUN_COMMAND` intents on the F-Droid build,
and a helper process that runs inside Termux. It only works with the signed F-Droid and Google Play
builds of Termux.

## Requirements

- Android 10 or newer on a 64-bit (arm64 or x86_64) device.
- [Termux](https://termux.dev) from [F-Droid](https://f-droid.org/packages/com.termux/) (0.118 or
  newer, recommended) or [Google Play](https://play.google.com/store/apps/details?id=com.termux)
  (Android 11 or newer).
- A network connection while a chat is working. The agent runs on your phone, but the model runs at
  the provider you connect.

## Installation

Prebuilt releases aren't published yet. Build and install Pipkin from source. The build needs a
JDK, the Android SDK, and Node.js and pnpm on your `PATH`; see [Development](#development) for the
exact toolchain.

```sh
git clone https://github.com/jeph/pipkin.git
cd pipkin
./gradlew :app:installDebug
```

`installDebug` installs on the device or emulator that `adb` is connected to. To build an APK
without installing it, run `./gradlew :app:assembleDebug` and find it in
`app/build/outputs/apk/debug/`.

## Usage

### Setup

On first launch, Pipkin walks through each step and checks it off as it completes:

1. **Install Termux** from F-Droid or Google Play. Copies of Termux from other sources are refused.
2. **Let Pipkin use Termux** by granting the `Run commands in Termux environment` permission (F-Droid
   build).
3. **Set up Termux** by pasting the setup command Pipkin copies for you into Termux.
4. **Allow notifications** and **background activity** for Pipkin and Termux, so chats keep running
   when you leave the app.
5. **Allow storage access** for Termux, so the agent can reach your phone's shared storage.
6. **Install the agent.** Pipkin installs its pinned Pi version in Termux. It takes a few minutes.
7. **Connect a model** by signing in with Claude or OpenAI, or by adding an OpenAI-compatible API.

The permission steps can be revisited later from **Settings → Permissions**.

### Models and providers

Subscriptions, API keys and providers live in **Settings**. An OpenAI-compatible provider needs a base URL,
usually ending in `/v1`, and an API key. Pipkin checks the connection by listing the provider's
models, and you can pick a default model and switch models per chat.

### Workspaces and chats

Workspaces in the sidebar are folders in Termux. Each holds its own chats, which you can rename,
fork from an earlier message, archive, and restore from the archive. The Files, Changes, and
Terminal views let you check the agent's work as it goes.

### Extensions

Pipkin loads the Pi extensions you install the usual Pi way, alongside the ones it ships. Turn the
bundled extensions on or off under **Settings → Pipkin extensions**. Extensions that support Pi's
RPC UI protocol show their prompts and widgets as native cards.

### Backups

**Settings → Backups** saves Pi's configuration, including your settings, sign-ins, models, and
extensions, without your chats. Backups are stored in Termux under `~/.pipkin/backups`. Restoring
one replaces the current configuration and leaves chats in place.

## Troubleshooting

### The setup command stops partway

Android can kill large `pkg` installs during setup. Run the setup command in Termux again; it picks
up where it left off. If it reports that packages need upgrading, run the command it names, such as
`yes | pkg upgrade -y`, and then the setup command again.

### Android won't ask for the Termux permission again

If the `Run commands in Termux environment` permission was denied, grant it from Pipkin's app info:
**Permissions → Additional permissions → Run commands in Termux environment → Allow**.

### Chats stop when the phone is locked

Allow background activity for both Pipkin and Termux in **Settings → Permissions**. Some phones name
this setting differently; the step in Pipkin shows where to find it when Android can't prompt for it.

### Pipkin's helper doesn't start

Open Termux to see why. Once the helper is running, Pipkin switches back on its own. If Termux asks
for the setup command, tap **Set up Termux** in Pipkin.

## Development

Building Pipkin needs:

- A JDK 17 or newer, through `JAVA_HOME` or `java` on your `PATH`, to start Gradle.
- The Android SDK with API level 37, NDK `30.0.16248370`, and CMake `4.1.2`. Point Gradle at the SDK
  with `ANDROID_HOME` or `sdk.dir` in `local.properties`.
- Node.js 22.19 or newer and [pnpm](https://pnpm.io), which build the host bundle.
- A network connection on the first build, which downloads the pinned tree-sitter grammars and the
  JDKs Gradle provisions for itself.

Run every check, including ktlint, detekt, Android lint, and the host's type check, lint, and
tests:

```sh
./gradlew check
```

Run only the host's checks:

```sh
cd host
pnpm install --frozen-lockfile
pnpm run check
```

Run the website locally:

```sh
cd site
pnpm install --frozen-lockfile
pnpm run dev
```

## Contributing

Pull requests are welcome. If you run into a bug, unexpected behavior, or a device-specific
problem, please [open an issue](https://github.com/jeph/pipkin/issues) with your phone model,
Android version, and Termux build. Reports from real-world use are especially helpful while the
project is young.

Do not report suspected security vulnerabilities in public issues. Follow the
[security policy](.github/SECURITY.md) to submit a private vulnerability report.

## License

Pipkin is released under the [MIT License](LICENSE). Third-party code, fonts, and icons that Pipkin
includes keep their own licenses, which are noted in the files they were copied into.
