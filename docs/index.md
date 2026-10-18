---
title: Pipkin guide
description: What Pipkin is, how it runs on your phone, and where to start.
---

Pipkin is an AI coding agent for Android. It reads your code, runs commands, edits files, and checks its work, all on your phone.

## How it fits together

- **The Pipkin app** shows chats, files, changes, terminals, and settings.
- **[Termux](https://termux.dev)** is a Linux environment for Android. The agent runs there and installs tools with `pkg`.
- **[Pi](https://pi.dev)** is the open-source agent. Setup installs it in Termux, and Pipkin runs one Pi process per chat.

The model you connect, such as Claude or OpenAI, runs remotely, so chats need a network connection.

## Where to go next

- **[Get started](/docs/get-started/)**: install Pipkin and Termux, and start a chat.
- **[Connect a model](/docs/providers/)**: sign in to Claude or OpenAI, or add an API key.
- **[Configure the agent](/docs/configure/)**: Pi's configuration files and backups.
- **[Extensions](/docs/extensions/)**: Pi extensions, skills, prompt templates, and the bundled extensions.
- **[Run in the background](/docs/background/)**: what keeps chats running and what can stop them.

Pipkin is in early development. Screens and settings may change.
