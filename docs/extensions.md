---
title: Extensions, skills, and prompts
description: Install Pi packages in Pipkin, write your own extensions, add skills and prompt templates, and see how extensions appear in Pipkin's interface.
---

Pipkin supports Pi's extensions, skills, and prompt templates. They go in Pipkin's config folder, `~/.pipkin/pi`. See [Configure the agent](/docs/configure/) for editing files there.

## Bundled extensions

Pipkin bundles extensions for ease of use, or where the experience it wants isn't possible through Pi's RPC protocol. The current bundled extensions are:

- **Pipkin todo** keeps a to-do list for the agent's current task, shown as a tab on the message box. The list is saved with the chat, and `/todos` shows it. It showcases Pipkin's [widgets](#how-extensions-look-in-pipkin). Its tool matches [rpiv-todo](https://github.com/juicesharp/rpiv-mono)'s.
- **Pipkin background** lets the agent run commands in the background, such as servers and long builds, and picks the chat back up when each one exits. Pipkin keeps the chat's agent and the phone awake while they run. See [Background tasks](/docs/background/#background-tasks).
- **Pipkin subagents** lets the agent hand self-contained tasks to subagents, which you can follow, steer, and stop. See [Subagents](#subagents).
- **Pipkin web access** lets the agent search the web and read pages, docs, and PDFs. `/websearch` opens its search curator. It's a copy of [pi-web-access](https://github.com/nicobailon/pi-web-access), changed to start faster on Android. Its settings, such as search API keys, go in `~/.pipkin/pi/web-search.json`; the [pi-web-access README](https://github.com/nicobailon/pi-web-access#readme) lists them.

Turn them off in **Settings → Extensions**. Idle chats reload to apply the change; working chats apply it when their agent next starts.

If you install `npm:pi-web-access` yourself, Pipkin drops its bundled copy. Likewise, if another extension registers a `subagent` tool, Pipkin subagents stands aside and leaves the tool to that extension.

## Subagents

A subagent is a second agent that the chat's agent hands a task to. It works in the same Pi process as the chat, then reports back, and the chat's agent waits for its subagents before it carries on. To work on several tasks in parallel, the agent starts several subagents in one reply. At most 6 run at once in a chat; further ones wait as **Queued** until one finishes. A subagent's final reply is all the chat's agent sees of its work; a reply longer than 50 KB is cut, with a note saying where the full transcript is.

### Explore and General

Pipkin subagents comes with two agents:

- **Explore** reads and searches the project without changing anything, and reports what it found. It gets only the read-only tools the chat has turned on: whichever of `read`, `grep`, `find`, and `ls` are on, plus extension tools marked read-only. With Pi's default tools, that is only `read`. A subagent's tool calls all go through the chat's agent, which can only run the chat's active tools; running them in the subagent instead would skip your permission extensions, and turning them on for the chat would change your tools.
- **General** has the same tools as the chat and works on a self-contained task, including changing files and running commands.

### Write your own agents

An agent is a Markdown file with frontmatter. Put your agents in `~/.pipkin/pi/agents/`. Agents in a project's `.pi/agents/` follow Pi's project trust: they load from [trusted projects](/docs/configure/#project-settings), and also from any project whose `.pi` folder has nothing Pi asks trust for, such as settings, extensions, skills, or prompts. A repository you clone with only `.pi/agents/` loads its agents, so read them before you ask for a subagent there. A project's agent replaces one of yours with the same name, and either can replace Explore or General. Save `~/.pipkin/pi/agents/reviewer.md`:

```markdown
---
name: Reviewer
description: Reviews a change for bugs and reports them without editing files.
tools: read, grep, find, ls
model: anthropic/claude-sonnet-4-5
thinking: high
---
Review the change you are given. List bugs first, with file paths and line numbers.
```

The body is added to the subagent's system prompt. Every field is optional:

- `name` defaults to the file's name without `.md`.
- `description` tells the chat's agent when to use the agent.
- `tools` is a list or a comma-separated line of tool names. The agent gets those of the chat's active tools; without `tools`, it gets all of them.
- `model` is written `provider/id`; without it, the subagent uses the chat's model.
- `thinking` is one of `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, or `max`.

Pipkin reads the agent files each time a subagent starts, so a new or changed agent works at once if the chat's agent asks for it by name. The list of agents the chat's agent is told about updates when you reload the chat. If a file can't be read, the chat's agent is told why when it asks for that agent.

Subagents never get the `subagent`, `todo`, or `background` tools, since those act on the chat's own subagents, to-do list, and background tasks. Subagents can't start subagents, use the to-do list, or run background tasks.

Subagents also don't get tools an extension registers for the model only, such as the `ask_user_question` tool of rpiv-ask-user-question. Pi lets only the chat's model call those, not a subagent working through the chat's agent.

### Follow, steer, and stop subagents

Each subagent shows in the chat as a tool row with its agent's name, its task, and its status or running time. While subagents run, the chat says how many it's waiting on.

The **Subagents** tab on the message box opens a sheet that lists the chat's subagents under **Waiting for you**, **Running**, and **Finished**. A failed subagent says why. **Stop all** stops every subagent of the chat.

Tap a subagent's row in the chat or in the sheet to open its screen. It opens in place of the chat, with **Subagent of** and the chat's title above it, and shows the subagent's status, turn, tokens, and time, its task, and its transcript. Back returns to the chat. While the subagent runs, its transcript updates as it works, as a chat's does: replies appear as they're written, and a running command shows its output so far. The screen also has a **Steer** box and **Stop**. A steering message reaches the subagent after its current tool call; until then it shows under **Steering** above the box, and **Edit** takes it back into the box. Once the subagent finishes, the screen is read only.

### Questions from subagents

A subagent's tool calls go through the chat's agent, so every call passes through your permission extensions, just as the chat's own calls do. Their questions appear in the chat's question card, and the subagent screen shows the same card.

Pi doesn't say which tool call raised a question, so Pipkin names a subagent only when no one else could have asked:

- When exactly one subagent has tool calls in progress and the chat's agent has none of its own, the card shows that subagent's agent name and task, and the subagent shows **Waiting for your answer**.
- When several subagents have tool calls in progress and the chat's agent has none, the card says **From a subagent**.
- Otherwise, the card names no one.

If a question arrives in a chat that isn't on screen, the chat appears in the inbox pill and Pipkin sends a notification. When Pipkin can name the subagent, the pill says, for example, **Explore needs you**, and the notification **Explore needs your answer**. Otherwise they say only that the chat needs you. While a subagent's screen is open, its chat counts as on screen.

Stopping a subagent cancels its question when Pipkin can tell the question is its own, that is, when the card names it. **Stop all**, and **Stop** in the message box or the notification, which stop every subagent along with the chat's agent, cancel the chat's questions when only subagents could have asked them. A question the chat's agent may have asked stays.

### Subagents in the archive

Subagents never become chats or archive rows of their own; they stay with their chat. An archived chat's row shows how many subagents it ran. Its subagent rows open read-only subagent screens. Restoring the chat restores its subagents, and deleting it deletes their transcripts too.

## Install a Pi package

Pi packages bundle extensions, skills, prompts, and themes from npm or git. Install one in either of two ways.

**List it in `settings.json`** under `packages`:

```json
{
  "packages": ["npm:@example/pi-tools"]
}
```

Then reload the chat: tap the tabs button at the top of the chat and choose **Reload agent**, or type `/reload`. Pi installs missing packages when the agent starts.

**Run Pi's install command** in a terminal tab. Pipkin's Pi isn't on the terminal's `PATH`, so give its path and config folder:

```sh
PI_CODING_AGENT_DIR=~/.pipkin/pi \
  node ~/.local/share/pipkin/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js \
  install npm:@example/pi-tools
```

Use `remove` to uninstall or `list` to list packages, then reload the chat. Without `PI_CODING_AGENT_DIR`, Pi installs into `~/.pi/agent`, which Pipkin ignores.

Pi's [packages documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/packages.md) covers git sources, version pinning, and partial loading. Packages run code with the agent's permissions; review them before installing.

### Update installed packages

**Settings → Extensions → Update Pi extensions** runs Pi's `pi update --extensions` with Pipkin's config folder. After you confirm, Pi updates every package listed in `~/.pipkin/pi/settings.json` to its latest version, except npm packages pinned to a version, such as `npm:@example/pi-tools@1.2.0`. The row shows Pi's progress, and a message says when the update finished or why it failed. Pi itself stays at the version Pipkin ships, and Pipkin's bundled extensions update with the app.

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
