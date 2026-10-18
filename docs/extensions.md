---
title: Extensions, skills, and prompts
description: Install Pi packages in Pipkin, write your own extensions, add skills and prompt templates, and see how extensions appear in Pipkin's interface.
---

Pipkin runs [Pi](https://pi.dev), so it works with Pi's extensions, skills, and prompt templates. Everything below goes in Pipkin's config folder, `~/.pipkin/pi` in Termux. See [Configure the agent](/docs/configure/) for how to edit files there.

## Bundled extensions

Pipkin installs two extensions of its own alongside Pi:

- **Pipkin todo** keeps a to-do list for the agent's current task, shown as a tab on the message box. The list is saved with the chat.
- **Pipkin web access** lets the agent search the web and read pages, docs, and PDFs. Type `/websearch` to open its search curator. It's a copy of [pi-web-access](https://github.com/nicobailon/pi-web-access), and it reads its settings, such as search provider API keys, from `~/.pipkin/pi/web-search.json`. The upstream README lists the options.

Turn either one off under **Settings → Pipkin extensions**. Idle chats reload to pick up the change; a working chat picks it up the next time its agent starts.

If you install `npm:pi-web-access` yourself, Pipkin leaves out its bundled copy so the two don't clash.

## Install a Pi package

Pi packages bundle extensions, skills, prompts, and themes, and come from npm or git. There are two ways to install one.

**Add it to `settings.json`.** List the package under `packages` in `~/.pipkin/pi/settings.json`:

```json
{
  "packages": ["npm:@example/pi-tools"]
}
```

Then reload the chat with **Chat actions → Reload agent** or `/reload`. Pi installs any listed package that's missing when the agent starts.

**Use Pi's install command.** Pipkin's copy of Pi isn't on the terminal's `PATH`, and the terminal doesn't point Pi at Pipkin's config folder, so give both explicitly. In a terminal tab:

```sh
PI_CODING_AGENT_DIR=~/.pipkin/pi \
  node ~/.local/share/pipkin/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js \
  install npm:@example/pi-tools
```

Swap `install` for `remove` to uninstall, or `list` to see what's installed, then reload the chat. Without `PI_CODING_AGENT_DIR`, Pi installs into `~/.pi/agent`, which Pipkin doesn't read.

Pi's [packages documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/packages.md) covers git sources, version pinning, and loading only part of a package. Packages run code on your phone with the agent's permissions, so review a package before installing it.

## Write your own extension

Put a TypeScript file in `~/.pipkin/pi/extensions/`, such as `~/.pipkin/pi/extensions/hello.ts`, and reload the chat. Pi's [extensions documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md) explains the API. An extension's commands show up when you type `/`.

## How extensions look in Pipkin

Pipkin talks to Pi through Pi's RPC mode, and draws an extension's interface with its own native controls instead of a terminal:

| An extension calls | Pipkin shows |
| --- | --- |
| `select`, `confirm`, `input`, `editor` | A card above the message box with choices, Yes and No, or a text field |
| `notify` | A notice in the chat, styled as info, a warning, or an error |
| `setStatus` | A status line under the chat's title |
| `setWidget` with lines of text | A tab on the message box that opens the widget |
| `setTitle` | The chat's title |
| `sendMessage` | A message in the chat |

When a `select`, `confirm`, `input`, or `editor` card comes up in a chat you can't see, Pipkin sends a notification, **Agent needs your answer**, with the chat's name and the question. That covers being in another app, having the screen off, and looking at another chat, a terminal, a file, the sidebar, or settings. Tap it to open the chat and answer. The notification goes away once the chat is on screen, or once the question is answered or expires.

Pi's RPC mode doesn't support everything a terminal can draw. Custom components, custom headers, footers, and editors, and widgets built from components don't appear in Pipkin; the rest of the extension still works. Pi's [RPC extension UI notes](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/rpc-extension-ui.md) list the details.

## Skills

A skill is a folder with a `SKILL.md` file that teaches the agent a task. Put skills in `~/.pipkin/pi/skills/`. Skills in a project's `.agents/skills/` or `.pi/skills/` load only once you [trust the project](/docs/configure/#project-settings). Pi offers skills to the agent on its own, and you can run one directly by typing `/skill:` and its name.

## Prompt templates

A prompt template is a Markdown file you run as a command. Save `~/.pipkin/pi/prompts/review.md`:

```markdown
---
description: Review the uncommitted changes
---
Review the uncommitted changes in this repository. Point out bugs first, then style.
```

Reload the chat, then type `/review`. Pi's [prompt template documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/prompt-templates.md) covers arguments such as `$1` and `$@`.

After adding or changing skills or prompts, reload the chat so they appear in the `/` menu.
