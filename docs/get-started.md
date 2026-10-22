---
title: Get started
description: Install Pipkin and Termux, walk through setup, and start your first chat.
---

Setup takes a few minutes, and Pipkin does most of it for you. It checks off each step as it finishes and explains every permission it asks for.

## What you need

- An Android phone or tablet running **Android 10 or newer**, with a 64-bit processor (almost every phone from the last few years).
- **Termux** from [F-Droid](https://f-droid.org/packages/com.termux/) (version 0.118 or newer) or [Google Play](https://play.google.com/store/apps/details?id=com.termux) (needs Android 11 or newer). The F-Droid version is recommended. Pipkin only works with these two signed builds and refuses copies of Termux from anywhere else.
- **An AI model to talk to**: a Claude Pro or Max subscription, an OpenAI Codex subscription, a Gemini, Z.ai Coding Plan or Kimi Code API key, or an API key for any OpenAI-compatible service. See [Connect a model](/docs/providers/).
- **A network connection** while a chat is working.

## Install Pipkin

[Download the latest APK](https://github.com/jeph/pipkin/releases/latest) from the releases page on your phone and open it to install. Android asks you to allow installs from your browser or file manager the first time. Later releases install over it as updates and keep your chats and settings.

### Build from source

With the Android SDK set up and your phone connected over `adb`, run:

```sh
git clone https://github.com/jeph/pipkin.git
cd pipkin
./gradlew :app:installDebug
```

The repository's README lists the full toolchain.

A build from source is named **Pipkin Dev** and installs beside a release. It keeps its files in Termux under `~/.pipkin-dev` instead of `~/.pipkin`, so wherever these docs say `~/.pipkin`, Pipkin Dev uses `~/.pipkin-dev`.

## Walk through setup

Open Pipkin and tap **Get started**. Each step shows **Done** once it's complete, and you can go back to any of them later from **Settings → Permissions**.

1. **Install Termux.** Pipkin links to F-Droid and Google Play. Install Termux, then come back to Pipkin. If you have a copy of Termux from somewhere else, Pipkin asks you to uninstall it first.
2. **Let Pipkin use Termux** (F-Droid build only). Allow **Run commands in Termux environment** when Android asks. This is how Pipkin starts the agent in Termux.
3. **Set up Termux.** Tap **Copy command & open Termux**. In Termux, long-press, tap **Paste**, and press Enter. The command installs Node.js and connects Termux to Pipkin, then switches back to Pipkin on its own. Android sometimes stops large installs partway; if that happens, paste the command again and it picks up where it left off.
4. **Notifications** (optional but recommended). Pipkin lets you know when a chat finishes or needs your answer while you're away.
5. **Run in the background** (optional but recommended). Keeps a chat going while the screen is off or you're in another app.
6. **Let Termux run in the background** (optional but recommended). The agent runs inside Termux, so Termux needs the same allowance. If Android can't ask directly, Pipkin shows where the setting is on your phone.
7. **Let Termux use phone storage** (optional but recommended). Lets the agent work on files outside Termux, such as in Downloads or Documents.
8. **Install the agent.** Tap **Install**. Pipkin installs Pi and its bundled extensions in Termux. It takes a few minutes, and you can leave the screen while it runs.
9. **Connect a model** (optional here). Sign in with **Claude** or **OpenAI**, add a **Gemini**, **Z.ai** or **Kimi** key, or add an **OpenAI-compatible API**. You can also do this later in **Settings → Providers**.

When you see **You're all set**, tap **Start using Pipkin**.

## Start your first chat

Pipkin organizes chats by **workspace**: a folder the agent works in, usually a project.

1. Open the sidebar and tap **Add workspace**.
2. Pick a folder under **Termux home** or **Phone storage**, or type a path in the search box, then tap **Add as workspace**. To start fresh, create a folder for your project first.
3. Tap **New chat** in the workspace, type what you want, and tap **Send**.

A few things to know while it works:

- **Pick a model** with the model chip under the message box. The same sheet sets how hard it should think, under **Reasoning effort**. Your choice applies to the chat and becomes the default for new chats.
- **Steer it.** Type a message while the agent is busy and it waits in the **Steering** box until the agent is ready for it. Tap **Send now** to interrupt, or **Edit** to change it.
- **Stop it** at any time with **Stop**.
- **Attach images** with the **+** button when the model can see them.
- **Type `/`** to see commands, such as `/model`, `/compact` to shrink a long chat, or `/fork` to branch from an earlier message.

## Check the agent's work

- **Files**: open the file explorer from a chat to browse and read files, with syntax highlighting for more than twenty languages. The viewer is read-only; to change a file, ask the agent or use a terminal.
- **Changes**: in a git repository, see exactly what the agent changed, file by file. Pipkin asks you to trust a repository before it runs git there, because a repository's own settings can run programs.
- **Terminal**: tap the chat's title and choose **New terminal** to open a Termux shell right next to your chat.
- **The chat's title** opens a sheet with the workspace's tabs and, under them, what the chat can do: start another chat, rename it, reload the agent after changing its configuration, copy the session's file path, or archive it. Terminals, files, and commits open the same sheet from their own titles.

On a foldable or tablet, open chats, files, changes, and terminals side by side with **Open in split screen**.

## If something goes wrong

**The setup command stops partway.** Paste it into Termux again; it resumes where it left off. If it says Node.js couldn't be installed, check your internet connection, then paste the command again.

**Android won't ask for the Termux permission again.** Grant it from Pipkin's app info: **Permissions → Additional permissions → Run commands in Termux environment → Allow**.

**Chats stop when the phone is locked.** Allow background activity for both Pipkin and Termux in **Settings → Permissions**. Some phones name this setting differently, and the step shows where to find it. [Run in the background](/docs/background/) explains what else can stop a chat.

**Pipkin says Termux isn't running or its helper didn't start.** Tap **Open Termux** to see why. Once Termux is running, Pipkin switches back on its own. If Termux asks for the setup command, tap **Set up Termux** in Pipkin.

**You reinstalled Termux.** Reinstalling removes Pipkin's setup, the agent, and its sign-ins from Termux. Pipkin walks you through setup again. A [backup](/docs/configure/#back-up-and-restore) brings back your settings, sign-ins, and models.
