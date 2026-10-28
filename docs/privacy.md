---
title: Privacy policy
description: What data Pipkin keeps on your phone, what leaves it, and where it goes.
---

_Last updated: October 28, 2026_

Pipkin is an app for running the [Pi](https://pi.dev) coding agent in [Termux](https://termux.dev) on your Android phone. It's made by Jeph, an independent developer, and is open source, so you can check everything on this page in [its code](https://github.com/jeph/pipkin).

## In short

- Pipkin has no accounts and no servers of its own. The developer doesn't receive your chats, files, sign-ins, or any other data from the app.
- Pipkin has no analytics, crash reporting, advertising, or tracking.
- What you send to the agent goes to the AI model provider you connect, and to the other services you or the agent use. Those services' own privacy policies cover what they do with it. A [local model](/docs/local-models/) you download runs on the phone, and what you send it stays there.

## What stays on your phone

Pipkin keeps its own settings, such as the theme and the folders you've opened as workspaces, in its private app storage. It also keeps there which chats you've archived, and when you last opened each chat, which auto-archive uses to find chats you haven't used in a while.

If you turn on [local models](/docs/local-models/) under **Settings → Experimental**, the models you download are kept in Pipkin's private app storage too, and aren't included in Android's backup. A local model runs entirely on the phone: your messages, the files and command output the agent reads, and the model's replies never leave it. The agent reaches the model over a connection that only accepts requests from the phone itself and only with a key Pipkin gives the agent, so other apps and devices can't use it.

Everything else lives in Termux's storage on your phone, which you can also open from Android's Files app:

- your chats and their history, including the transcripts of the subagents a chat runs
- the agent's configuration, including your subscription sign-ins and API keys
- extensions, skills, and prompt templates you add
- backups you make in **Settings → Configuration → Backups**, which are written to a folder in Termux and aren't uploaded anywhere
- the files and projects you and the agent work on

[Configure the agent](/docs/configure/) lists where each of these is kept.

Pipkin opts out of Android's backup to your Google account and of the transfer to a new phone, so neither copies its settings. Chats, sign-ins, and API keys are in Termux, not in Pipkin's app storage, so Pipkin's settings don't include them either way.

## What leaves your phone

Pipkin sends data off the phone only to do what you ask, and only to these services:

- **Your AI model provider.** The agent sends your messages, the photos you attach, and the parts of your files and command output it reads to the provider you connect, such as Anthropic, OpenAI, Google, Z.ai, Moonshot AI, OpenRouter, or an OpenAI-compatible API you add. OpenRouter passes requests on to the company that runs the model you pick. When you sign in with a subscription, you sign in on the provider's own website. Pipkin also asks a provider you add for its list of models, using the API key you gave it, if any; for Z.ai, Kimi and OpenRouter, that's the list of models your key can use.
- **Web search and page fetching.** When the agent searches the web, the bundled web access extension sends the search query to a search service. By default that's [Exa](https://exa.ai), which needs no account, or OpenAI's web search if you've connected OpenAI; you can choose others in its settings. When the agent reads a web page, PDF, or video, it downloads it from the site that hosts it. Depending on the extraction services and credentials you've set up for the extension, such as Gemini or Datalab, it may also send the whole page, PDF, or video link to one of them to read it, separately from your chat's model provider. You can turn the extension off in **Settings → Extensions**.
- **Downloads during setup and updates.** Setup downloads Node.js from Termux's package mirrors and Pi from the [npm registry](https://www.npmjs.com). Installing or updating extensions downloads them from where they're published.
- **Local model downloads.** When you tap **Download** in **Settings → Experimental → Local models**, Pipkin downloads that model's file from [Hugging Face](https://huggingface.co). Nothing about you or your chats is sent with the request.
- **Commands and extensions.** The agent runs commands in Termux, and those can reach any service, for example when it runs `git push` or installs a package. Extensions you install run with the same access. What they send is up to the command or extension, not Pipkin.

Like any download or request, these services see your phone's IP address.

## Permissions

Pipkin asks for these Android permissions:

- **Run commands in Termux**, to start the agent and its helper in Termux.
- **Network access**, which Android requires even for Pipkin to talk to its helper in Termux on the same phone.
- **Notifications**, to tell you when a chat finishes or needs your answer.
- **Running in the background and keeping the phone awake**, so a chat can keep working while you use other apps or lock the screen.
- **Asking to uninstall an app**, used only to offer removing a copy of Termux that Pipkin can't use. Android asks you to confirm.

When you attach a photo, Android's photo picker gives Pipkin only the photos you choose. Pipkin itself doesn't ask for access to your contacts, location, camera, microphone, or the rest of your photos and files.

Setup also asks you to give Termux two permissions in Termux's own name:

- **Access to your phone's shared storage**, such as Downloads, Documents, and your photos, so the agent can work on files outside Termux. With it, the agent can read and change files there when a chat calls for it, and send what it reads to your model provider like any other file. You can turn it off in Android's settings for Termux.
- **Running in the background without battery limits**, so the agent keeps working while the screen is off.

## Deleting your data

Uninstalling Pipkin deletes its settings and any local models you downloaded, but not what's in Termux. To delete your chats, sign-ins, backups, and the agent, delete the `~/.pipkin` and `~/.local/share/pipkin` folders in Termux, or uninstall Termux, which deletes everything in it. To delete data a model provider or other service holds, use that service's own settings or contact it.

## Children

Pipkin is a developer tool that isn't directed at children under 13.

## Changes

If Pipkin's handling of data changes, this page is updated with a new date at the top.

## This website

This website has no analytics, cookies, or tracking. It's hosted by [Vercel](https://vercel.com), which, like any web host, receives your IP address and browser details when you open a page. Pipkin's releases are downloaded from [GitHub](https://github.com/jeph/pipkin/releases).

## Contact

Send questions about privacy to [github@jeph.ph](mailto:github@jeph.ph).
