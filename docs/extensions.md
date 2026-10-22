---
title: Extensions, skills, and prompts
description: Install Pi packages in Pipkin, write your own extensions, add skills and prompt templates, and see how extensions appear in Pipkin's interface.
---

Pipkin supports Pi's extensions, skills, and prompt templates. They go in Pipkin's config folder, `~/.pipkin/pi`. See [Configure the agent](/docs/configure/) for editing files there.

## Bundled extensions

Pipkin bundles extensions for ease of use, or where the experience it wants isn't possible through Pi's RPC protocol. The current bundled extensions are:

- **Pipkin todo** keeps a to-do list for the agent's current task, shown as a tab on the message box. The list is saved with the chat, and `/todos` shows it. It showcases Pipkin's [widgets](#how-extensions-look-in-pipkin). Its tool matches [rpiv-todo](https://github.com/juicesharp/rpiv-mono)'s.
- **Pipkin background** (experimental) lets the agent run commands in the background, such as servers and long builds, and picks the chat back up when each one exits. Pipkin keeps the chat's agent and the phone awake while they run. See [Background tasks](/docs/background/#background-tasks).
- **Pipkin web access** lets the agent search the web and read pages, docs, and PDFs. `/websearch` opens its search curator. It's a copy of [pi-web-access](https://github.com/nicobailon/pi-web-access), changed to start faster on Android. Its settings, such as search API keys, go in `~/.pipkin/pi/web-search.json`; the [pi-web-access README](https://github.com/nicobailon/pi-web-access#readme) lists them.

Turn them off in **Settings → Pipkin extensions**. Idle chats reload to apply the change; working chats apply it when their agent next starts.

If you install `npm:pi-web-access` yourself, Pipkin drops its bundled copy.

## Install a Pi package

Pi packages bundle extensions, skills, prompts, and themes from npm or git. Install one in either of two ways.

**List it in `settings.json`** under `packages`:

```json
{
  "packages": ["npm:@example/pi-tools"]
}
```

Then reload the chat: tap its title and choose **Reload agent**, or type `/reload`. Pi installs missing packages when the agent starts.

**Run Pi's install command** in a terminal tab. Pipkin's Pi isn't on the terminal's `PATH`, so give its path and config folder:

```sh
PI_CODING_AGENT_DIR=~/.pipkin/pi \
  node ~/.local/share/pipkin/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js \
  install npm:@example/pi-tools
```

Use `remove` to uninstall or `list` to list packages, then reload the chat. Without `PI_CODING_AGENT_DIR`, Pi installs into `~/.pi/agent`, which Pipkin ignores.

Pi's [packages documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/packages.md) covers git sources, version pinning, and partial loading. Packages run code with the agent's permissions; review them before installing.

### Update installed packages

**Settings → Agent → Update Pi extensions** runs Pi's `pi update --extensions` with Pipkin's config folder. After you confirm, Pi updates every package listed in `~/.pipkin/pi/settings.json` to its latest version, except npm packages pinned to a version, such as `npm:@example/pi-tools@1.2.0`. The row shows Pi's progress, and a message says when the update finished or why it failed. Pi itself stays at the version Pipkin ships, and Pipkin's bundled extensions update with the app.

Chats can't start while the update runs. Afterwards, chats that aren't working reload with the new versions; a working chat picks them up the next time its agent starts.

## Write your own extension

Put a TypeScript file in `~/.pipkin/pi/extensions/`, such as `hello.ts`, and reload the chat. Pi's [extensions documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md) covers the API. Extension commands appear when you type `/`.

## How extensions look in Pipkin

Pipkin talks to Pi over Pi's RPC mode and draws extension UI with native controls:

| An extension calls | Pipkin shows |
| --- | --- |
| `select`, `confirm`, `input`, `editor` | A card above the message box with choices, Yes and No, or a text field |
| `notify` | A notice in the chat, styled as info, a warning, or an error |
| `setStatus` | A status line under the chat's title |
| `setWidget` with lines of text | A tab on the message box that opens the widget |
| `setTitle` | The chat's title |
| `sendMessage` | A message in the chat |

If a `select`, `confirm`, `input`, or `editor` card appears in a chat that isn't on screen, Pipkin sends an **Agent needs your answer** notification with the chat's name and the question. Tap it to open the chat. It clears once the chat is on screen or the question is answered or expires.

RPC mode can't show custom components, headers, footers, editors, or component widgets; the rest of the extension works. See Pi's [RPC extension UI notes](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/rpc-extension-ui.md).

## Skills

A skill is a folder with a `SKILL.md` that teaches the agent a task. Put skills in `~/.pipkin/pi/skills/`. Skills in a project's `.agents/skills/` or `.pi/skills/` load only from [trusted projects](/docs/configure/#project-settings). The agent uses skills on its own; type `/skill:` and a name to run one.

## Prompt templates

A prompt template is a Markdown file run as a command. Save `~/.pipkin/pi/prompts/review.md`:

```markdown
---
description: Review the uncommitted changes
---
Review the uncommitted changes in this repository. Point out bugs first, then style.
```

Reload the chat, then type `/review`. Pi's [prompt template documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/prompt-templates.md) covers arguments such as `$1` and `$@`.

Reload the chat after adding or changing skills or prompts.
