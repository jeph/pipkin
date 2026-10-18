---
title: Get started
description: Install Pipkin and Termux, walk through setup, and start your first chat.
---

Setup takes a few minutes. Pipkin checks off each step and explains each permission it asks for.

## What you need

- A phone or tablet running **Android 10 or newer** with a 64-bit processor.
- **Termux** from [F-Droid](https://f-droid.org/packages/com.termux/) (0.118 or newer, recommended) or [Google Play](https://play.google.com/store/apps/details?id=com.termux) (Android 11 or newer). Pipkin refuses Termux from other sources.
- **A model**: a Claude Pro or Max subscription, an OpenAI Codex subscription, a Gemini, Z.ai Coding Plan or Kimi Code API key, or a key for any OpenAI-compatible API. See [Connect a model](/docs/providers/).
- **A network connection** while a chat works.

## Install Pipkin

There are no prebuilt releases yet. With the Android SDK set up and your phone connected over `adb`, run:

```sh
git clone https://github.com/jeph/pipkin.git
cd pipkin
./gradlew :app:installDebug
```

The README lists the full toolchain.

## Walk through setup

Open Pipkin and tap **Get started**. Finished steps show **Done**. You can revisit any step in **Settings → Permissions**.

1. **Install Termux** from F-Droid or Google Play, then return to Pipkin. If you have Termux from another source, Pipkin asks you to uninstall it first.
2. **Let Pipkin use Termux** (F-Droid only). Allow **Run commands in Termux environment** when Android asks.
3. **Set up Termux.** Tap **Copy command & open Termux**. In Termux, long-press, tap **Paste**, and press Enter. The command installs Node.js, connects Termux to Pipkin, and switches back to Pipkin. If Android stops it partway, paste it again; it resumes.
4. **Notifications** (recommended). Pipkin tells you when a chat finishes or needs an answer while you're away.
5. **Run in the background** (recommended). Keeps chats working while the screen is off or you're in another app.
6. **Let Termux run in the background** (recommended). The agent runs in Termux, so Termux needs this too. If Android can't ask, Pipkin shows where the setting is.
7. **Let Termux use phone storage** (recommended). Lets the agent work on files outside Termux, such as Downloads.
8. **Install the agent.** Tap **Install**. Pipkin installs Pi and its bundled extensions. This takes a few minutes and continues if you leave the screen.
9. **Connect a model** (optional). Sign in with **Claude** or **OpenAI**, add a **Gemini**, **Z.ai** or **Kimi** key, or add an **OpenAI-compatible API**. You can also do this later in **Settings → Providers**.

At **You're all set**, tap **Start using Pipkin**.

## Start your first chat

Chats are grouped by **workspace**, the folder the agent works in.

1. Open the sidebar and tap **Add workspace**.
2. Pick a folder under **Termux home** or **Phone storage**, or type a path, then tap **Add as workspace**.
3. Tap **New chat**, type what you want, and tap **Send**.

While the agent works:

- **Model and reasoning**: set them with the chips under the message box. Your choice applies to the chat and becomes the default for new chats.
- **Steer**: messages you send while the agent is busy wait in the **Steering** box. Tap **Send now** to interrupt, or **Edit** to change one.
- **Stop**: tap **Stop**.
- **Images**: attach them with **+** if the model accepts images.
- **Commands**: type `/` to list them, such as `/model`, `/compact` to shrink a long chat, and `/fork` to branch from an earlier message.

## Check the agent's work

- **Files**: browse and read files from a chat. The viewer is read-only; to change a file, ask the agent or use a terminal.
- **Changes**: in a git repository, see what the agent changed, file by file. Pipkin asks you to trust a repository before running git in it, because a repository's settings can run programs.
- **Terminal**: **Chat actions → New terminal** opens a Termux shell beside the chat.
- **Chat actions** also renames, reloads, or archives a chat, and copies its session file path.

On a foldable or tablet, **Open in split screen** shows two panes side by side.

## Troubleshooting

**The setup command stops partway.** Paste it into Termux again; it resumes. If Node.js fails to install, check your connection and try again.

**Android won't ask for the Termux permission again.** In Pipkin's app info, go to **Permissions → Additional permissions → Run commands in Termux environment → Allow**.

**Chats stop when the phone is locked.** Allow background activity for Pipkin and Termux in **Settings → Permissions**. See [Run in the background](/docs/background/).

**Termux isn't running or its helper didn't start.** Tap **Open Termux**. Pipkin switches back once Termux is up. If Termux asks for the setup command, tap **Set up Termux** in Pipkin.

**You reinstalled Termux.** Reinstalling removes Pipkin's setup, the agent, and its sign-ins. Pipkin runs setup again. A [backup](/docs/configure/#back-up-and-restore) restores your settings, sign-ins, and models.
