---
title: Configure the agent
description: Where Pipkin keeps Pi's configuration, how to edit it, how to give the agent standing instructions, and how backups work.
---

The agent is [Pi](https://pi.dev), configured with files in its config folder.

## Where the configuration lives

Pipkin's config folder is `~/.pipkin/pi` in Termux, or `/data/data/com.termux/files/home/.pipkin/pi` in full. **Settings → Agent → Config folder** copies the path.

A standalone Pi uses `~/.pi/agent`. Pipkin and a standalone Pi don't share settings, sign-ins, or extensions.

| File or folder | What it holds |
| --- | --- |
| `settings.json` | Pi's settings, including the default model and installed packages |
| `auth.json` | Subscription sign-ins and API keys |
| `models.json` | OpenAI-compatible providers and their models (see [Connect a model](/docs/providers/)) |
| `AGENTS.md` | Instructions for every chat |
| `APPEND_SYSTEM.md` | Text appended to the system prompt |
| `SYSTEM.md` | A replacement system prompt |
| `extensions/`, `skills/`, `prompts/`, `themes/` | Your extensions, skills, prompt templates, and themes |
| `web-search.json` | Settings for the bundled web access extension |
| `sessions/` | Chat history |

Pi's [configuration documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/configuration.md) covers every file and setting. Read `~/.pi/agent` there as `~/.pipkin/pi`.

## Edit the files

The file viewer is read-only. To change a file:

- **Ask the agent**, for example: "Add a rule to `~/.pipkin/pi/AGENTS.md` that tests run with `pytest -q`."
- **Use a terminal**: open **Chat actions → New terminal** and use an editor such as `nano` (`pkg install nano`).

To browse the folder, add `~/.pipkin/pi` as a workspace, or choose **Show hidden files** in the file explorer.

Reload to apply changes:

- **Chat actions → Reload agent**, or `/reload`, reloads the current chat. Pipkin asks first if the agent is working.
- **Settings → Agent → Reload all agents** reloads every idle chat.

A reload restarts the agent and keeps the conversation.

## Give the agent standing instructions

Instructions for every chat go in `~/.pipkin/pi/AGENTS.md`. Instructions for one project go in an `AGENTS.md` in its folder. Pi reads both, plus any `AGENTS.md` in parent folders.

Pipkin already tells the agent it runs on Android in Termux. To add to the system prompt instead, use `APPEND_SYSTEM.md`.

## Project settings

A project's `.pi` folder can hold its own Pi settings, extensions, skills, and prompts. These can run code, so Pi loads them only from trusted folders. Pipkin can't show Pi's trust prompt, so list trusted folders by full path in `~/.pipkin/pi/trust.json`:

```json
{
  "/data/data/com.termux/files/home/projects/my-app": true
}
```

Trust extends to subfolders. `AGENTS.md` files load without trust.

## Back up and restore

In **Settings → Agent → Backups**, tap **Back up now**. A backup holds settings, sign-ins, models, extensions, skills, prompts, and themes, but not chats.

Backups are saved in `~/.pipkin/backups`. The Android Files app can copy them elsewhere. Uninstalling Termux or clearing its data deletes them.

To restore, tap **Restore** next to a backup. It replaces the current configuration; chats are kept. Pipkin stops running chats first. Quit any `pi` you started yourself with Pipkin's config folder before restoring.
