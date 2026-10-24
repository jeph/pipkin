---
title: Privacy policy
description: What data Pipkin keeps on your phone, what leaves it, and where it goes.
---

_Last updated: October 24, 2026_

Pipkin is an app for running the [Pi](https://pi.dev) coding agent in [Termux](https://termux.dev) on your Android phone. It's made by Jeph, an independent developer, and is open source, so you can check everything on this page in [its code](https://github.com/jeph/pipkin).

## In short

- Pipkin has no accounts and no servers of its own. The developer doesn't receive your chats, files, sign-ins, or any other data from the app.
- Pipkin has no analytics, crash reporting, advertising, or tracking.
- What you send to the agent goes to the AI model provider you connect, and to the other services you or the agent use. Those services' own privacy policies cover what they do with it.

## What stays on your phone

Pipkin keeps its own settings, such as the theme and the folders you've opened as workspaces, in its private app storage.

Everything else lives in Termux's storage on your phone, which you can also open from Android's Files app:

- your chats and their history
- the agent's configuration, including your subscription sign-ins and API keys
- extensions, skills, and prompt templates you add
- backups you make in **Settings → Backups**, which are written to a folder in Termux and aren't uploaded anywhere
- the files and projects you and the agent work on

[Configure the agent](/docs/configure/) lists where each of these is kept.

If you've turned on Android's backup to your Google account, Android may include Pipkin's settings in it, as it does for other apps. Pipkin's private connection key to its helper in Termux is left out of those backups. Chats, sign-ins, and API keys are in Termux, not in Pipkin's app storage, so Pipkin's own backup doesn't contain them.

## What leaves your phone

Pipkin sends data off the phone only to do what you ask, and only to these services:

- **Your AI model provider.** The agent sends your messages, the photos you attach, and the parts of your files and command output it reads to the provider you connect, such as Anthropic, OpenAI, Google, Z.ai, Moonshot AI, or an OpenAI-compatible API you add. When you sign in with a subscription, you sign in on the provider's own website. Pipkin also asks a provider you add for its list of models, using the API key you gave it.
- **Web search and page fetching.** When the agent searches the web, the bundled web access extension sends the search query to a search service. By default that's [Exa](https://exa.ai), which needs no account, or OpenAI's web search if you've connected OpenAI; you can choose others in its settings. When the agent reads a web page, PDF, or video, it downloads it from the site that hosts it, or from a service you've turned on for that. You can turn the extension off in **Settings → Pipkin extensions**.
- **Downloads during setup and updates.** Setup downloads Node.js from Termux's package mirrors and Pi from the [npm registry](https://www.npmjs.com). Installing or updating extensions downloads them from where they're published.
- **Commands and extensions.** The agent runs commands in Termux, and those can reach any service, for example when it runs `git push` or installs a package. Extensions you install run with the same access. What they send is up to the command or extension, not Pipkin.

Like any download or request, these services see your phone's IP address.

## Permissions

Pipkin asks for these Android permissions:

- **Run commands in Termux**, to start the agent and its helper in Termux.
- **Network access**, which Android requires even for Pipkin to talk to its helper in Termux on the same phone.
- **Notifications**, to tell you when a chat finishes or needs your answer.
- **Running in the background and keeping the phone awake**, so a chat can keep working while you use other apps or lock the screen.
- **Asking to uninstall an app**, used only to offer removing a copy of Termux that Pipkin can't use. Android asks you to confirm.

When you attach a photo, Android's photo picker gives Pipkin only the photos you choose. Pipkin doesn't ask for access to your contacts, location, camera, microphone, or the rest of your photos and files.

## Deleting your data

Uninstalling Pipkin deletes its settings, but not what's in Termux. To delete your chats, sign-ins, backups, and the agent, delete the `~/.pipkin` and `~/.local/share/pipkin` folders in Termux, or uninstall Termux, which deletes everything in it. To delete data a model provider or other service holds, use that service's own settings or contact it.

## Children

Pipkin is a developer tool that isn't directed at children under 13.

## Changes

If Pipkin's handling of data changes, this page is updated with a new date at the top.

## Contact

Send questions about privacy to [github@jeph.ph](mailto:github@jeph.ph).
