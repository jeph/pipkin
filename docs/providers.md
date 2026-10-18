---
title: Connect a model
description: Sign in with a Claude or OpenAI subscription, add a Gemini, Z.ai or Kimi API key or an OpenAI-compatible API, and choose models per chat.
---

The agent runs on your phone; the model runs at a provider. Pipkin supports:

| Choice | What you need |
| --- | --- |
| **Claude** | A Claude Pro or Max subscription |
| **OpenAI** | An OpenAI Codex subscription |
| **Gemini** | A Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey) |
| **Z.ai** | An API key for the [GLM Coding Plan](https://z.ai/subscribe) |
| **Kimi** | An API key from the [Kimi Code console](https://www.kimi.com/code) |
| **OpenAI-compatible API** | A base URL and API key for any OpenAI-compatible service, including gateways and self-hosted servers |

You can connect several and choose one per chat. Add them during setup or in **Settings → Providers → Add a provider**.

## Sign in with a subscription

1. Choose **Claude** or **OpenAI**.
2. Pick **Browser login (default)**. Pipkin opens the sign-in page in your browser.
3. Sign in. When the browser returns you to Pipkin, it shows **Signed in**.

If the browser doesn't return to Pipkin, copy the code or the final address from the browser, paste it into the sign-in sheet, and tap **Continue**.

If browser login fails:

- **Copy code login** (Claude): sign in, then paste the code Anthropic shows.
- **Device code login** (OpenAI): open the link, enter the code Pipkin shows, and Pipkin finishes.

To sign out, open the subscription in **Settings → Providers** and tap **Sign out**.

### Use your Claude plan

Anthropic bills Claude Pro and Max sign-ins used outside Claude Code as extra usage, at API rates. After you sign in to Claude, Pipkin offers [pi-anthropic-auth](https://github.com/gotgenes/pi-anthropic-auth), a third-party Pi plugin that makes Pipkin's requests look like Claude Code's so your plan covers them. This breaks Anthropic's terms and could get your account suspended.

Tap **Install plugin** to install it, or **Skip** to keep paying for extra usage. Once it's installed, Pipkin stops offering it. To remove it, uninstall `npm:@gotgenes/pi-anthropic-auth` as described in [Extensions](/docs/extensions/).

## Add a Gemini, Z.ai or Kimi key

1. Choose **Gemini**, **Z.ai** or **Kimi**.
2. Paste your API key and tap **Continue**. Pipkin shows **Key saved**.

Pi knows these providers' models, context windows, and reasoning levels. Usage is billed to your Gemini API account, GLM Coding Plan, or Kimi Code membership. Pipkin doesn't check the key on save; a wrong key shows up as an error in the first chat.

Gemini needs an API key, because Google doesn't let third-party apps use Google AI Pro or Ultra subscriptions.

To remove a key, open the provider in **Settings → Providers** and tap **Remove key**.

## Add an OpenAI-compatible API

1. In **Settings → Providers**, tap **Add a provider**, then **OpenAI-compatible API**.
2. Fill in:
   - **Provider ID**: a short name, such as `deepinfra`, in lowercase letters, digits, and dashes.
   - **Base URL**: the API's address, usually ending in `/v1`, such as `https://api.example.com/v1`.
   - **API key**: your provider's key.
   - **API type**: **Chat Completions** works almost everywhere. Choose **Responses** if your provider supports OpenAI's Responses API.
3. Tap **Check**. Pipkin fetches the model list and shows **Connected**.
4. Tap **Add models** and pick from **Available models**, or type a **Model ID** under **Custom models**.
5. Tap **Save**.

Pipkin fills in details for popular models. Tap a model to set whether it **Accepts images**, whether it **Supports thinking** and at which levels, its **Context window** and **Max output tokens**, and its cost per million tokens, which Pipkin uses to show a chat's cost.

If the check fails, Pipkin says why:

- **API key not accepted**: check the key is complete and active.
- **No models list at this URL**: the base URL usually ends in `/v1`.
- **Not an OpenAI-compatible API**: the URL points at a website, not the API.
- **Couldn't reach the server** or **No response**: check the address and your connection.

To remove a provider, open it and tap **Remove provider**. This deletes the provider, its models, and its key.

## Choose a model for a chat

Tap the model chip under the message box to search and switch models. The **Reasoning effort** chip sets how much the model thinks, and offers only the levels the model supports. `/model` and `/thinking` do the same.

Your choice applies to the current chat and becomes the default for new chats. To change which models are listed, open a provider in **Settings → Providers** and add or remove models.

## Where it's stored

Pipkin saves providers in Pi's configuration files in `~/.pipkin/pi`:

- `auth.json`: subscription sign-ins and Gemini, Z.ai, and Kimi keys.
- `models.json`: OpenAI-compatible providers, their models, and their keys.
- `settings.json`: the default model and which models are listed.

These files hold your keys and tokens; keep them private. [Backups](/docs/configure/#back-up-and-restore) include them.

### Other providers

Pi reads `models.json` directly, so you can add any provider Pi supports by editing it. For example, a local [Ollama](https://ollama.com) server:

```json
{
  "providers": {
    "ollama": {
      "baseUrl": "http://localhost:11434/v1",
      "api": "openai-completions",
      "apiKey": "ollama",
      "models": [{ "id": "qwen2.5-coder:7b" }]
    }
  }
}
```

Pipkin keeps entries it doesn't manage when it saves the file. Pi's [models documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/models.md) lists every option. After editing, use **Chat actions → Reload agent** to load the change.
