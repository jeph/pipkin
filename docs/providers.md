---
title: Connect a model
description: Sign in with a Claude or OpenAI subscription, add a Gemini, Z.ai, Kimi or OpenRouter API key or an OpenAI-compatible API, and choose models per chat.
---

The agent runs on your phone, but the AI model it talks to runs at a provider. Pipkin supports these:

| Choice | What you need |
| --- | --- |
| **Claude** | A Claude Pro or Max subscription |
| **OpenAI** | An OpenAI Codex subscription |
| **Gemini** | A Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey) |
| **Z.ai** | An API key for the [GLM Coding Plan](https://z.ai/subscribe) |
| **Kimi** | An API key from the [Kimi Code console](https://www.kimi.com/code) |
| **OpenRouter** | An API key from [OpenRouter](https://openrouter.ai/settings/keys) |
| **OpenAI-compatible API** | A base URL, and an API key if the service needs one, from any service that speaks the OpenAI API, including gateways and servers you run yourself |

You can connect several and switch between them per chat. Add them during setup, or later in **Settings → Providers and models → Add a provider**.

## Sign in with a subscription

1. Choose **Claude** or **OpenAI**.
2. Pick **Browser login (default)**. Pipkin opens the sign-in page in your browser.
3. Sign in. When the browser sends you back, Pipkin shows **Signed in**.

If the browser doesn't come back to Pipkin on its own, copy the code or the full address the browser ends up on, paste it into the sign-in sheet, and tap **Continue**.

The other options are useful when the browser flow doesn't work on your phone:

- **Copy code login** (Claude): sign in, then paste the code Anthropic shows you.
- **Device code login** (OpenAI): Pipkin shows a code; open the link, enter the code, and Pipkin finishes on its own.

To sign out, open the subscription in **Settings → Providers and models**, tap **Sign out**, and confirm.

## Add a Gemini, Z.ai, Kimi or OpenRouter key

1. Choose **Gemini**, **Z.ai**, **Kimi** or **OpenRouter**.
2. Paste your API key and tap **Continue**. Pipkin saves the key and opens **Add models** for the provider.
3. Search the list and tap the models you want, then tap **Done** and **Save**.

For Z.ai, Kimi and OpenRouter, **Add models** lists the models the provider says your key can use, so new models show up there before Pi knows them, and no models are switched on until you pick some. OpenRouter offers hundreds of models; free ones end in `:free`. If you add an OpenRouter key and pick no models, Pi starts new chats on its own OpenRouter default, a paid model, so check the model chip before you send. If the provider's list can't be read, Pipkin explains why and shows the models Pi knows instead. Gemini always shows the models Pi knows, all switched on.

Pipkin uses Pi's built-in support for these providers, so the models Pi knows come with their context windows and reasoning levels filled in. Usage counts against your Gemini API billing, your GLM Coding Plan, your Kimi Code membership, or your OpenRouter credits. Pipkin doesn't check the key when you save it; a wrong key shows up when Pipkin lists the provider's models, or as an error in the first chat.

Google doesn't let third-party apps use a Google AI Pro or Ultra subscription, so Gemini needs an API key rather than a Google sign-in.

To remove a key, open the provider in **Settings → Providers and models**, tap **Remove key**, and confirm. This deletes the key from your phone only; revoke it with the provider if you no longer want it to work.

## Add an OpenAI-compatible API

1. In **Settings → Providers and models**, tap **Add a provider**, then **OpenAI-compatible API**.
2. Fill in:
   - **Provider ID**: a short name for it, such as `deepinfra`. Lowercase letters, digits, and dashes.
   - **Base URL**: the API's address, which usually ends in `/v1`, such as `https://api.example.com/v1`.
   - **API key**: the key from your provider. Leave it empty for a server that needs none, such as Ollama or llama.cpp running on your network.
   - **API type**: **Chat Completions** works almost everywhere. Choose **Responses** if your provider supports OpenAI's newer Responses API.
3. Tap **Check**. Pipkin asks the API for its list of models and shows **Connected** when it answers.
4. Tap **Add models** and pick from **Available models**, or type a **Model ID** under **Custom models**.
5. Tap **Save**.

Pipkin fills in what it knows about popular models. Tap a model to adjust its settings: whether it **Accepts images**, whether it **Supports thinking** and at which levels, its **Context window** and **Max output tokens**, and its cost per million tokens if you want Pipkin to show what a chat costs.

If the check fails, Pipkin explains why. The most common fixes:

- **API key not accepted**: check the key is complete and still active.
- **This server needs an API key**: the server turned down a check without a key. Enter the key it expects.
- **No models list at this URL**: the base URL usually needs to end in `/v1`.
- **Not an OpenAI-compatible API**: the URL points at a website rather than the API.
- **Couldn't reach the server** or **No response**: check the address and that your phone is online.

If the check connects but Pipkin says **The server lists no models**, the server has no model to offer yet. Download or load one on it, for example with `ollama pull`, then tap **Check** again, or add a model by its ID under **Custom models**.

To remove a provider, open it and tap **Remove provider**. Pipkin forgets the provider, its models, and its API key.

## Choose a model for a chat

Under the message box, the model chip shows the current model. Tap it to search and switch. Under the models, **Reasoning effort** sets how much the model thinks before answering; it only offers the levels the current model supports, and the chip next to the model chip shows the one picked. Tapping that chip, or typing `/model` or `/thinking`, opens the same sheet, and `/thinking high` sets a level directly.

Whatever you pick applies to the current chat and becomes the default for new chats. To control which models show up in the list, open a provider in **Settings → Providers and models** and add or remove its models.

## Where it's stored

Pipkin saves providers in Pi's own configuration files, in Termux under `~/.pipkin/pi`:

- `auth.json` holds subscription sign-ins and the Gemini, Z.ai, Kimi and OpenRouter keys.
- `models.json` holds OpenAI-compatible providers, their models, and their API keys.
- `settings.json` holds the default model and which models appear in the list.

These files contain your keys and sign-in tokens, so keep them private. A [backup](/docs/configure/#back-up-and-restore) includes them.

### Other providers

Pipkin's screens cover the choices above, but Pi reads `models.json` directly, so you can add any provider Pi supports by editing the file. For example, a local [Ollama](https://ollama.com) server:

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

Pipkin keeps anything it doesn't manage when it saves the file. Pi's [models documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/models.md) covers every option. After editing, use **Chat actions → Reload agent** in an open chat to load the change.
