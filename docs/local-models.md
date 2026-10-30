---
title: Local models
description: Download a small model and run it on your phone, with no provider and no network once it's downloaded. Experimental.
---

Pipkin can run a small AI model on your phone itself, instead of sending your chat to a provider. Once the model is downloaded, a chat that uses it needs no network and no account, and what you send it never leaves the phone.

Local models are an experiment. The models a phone can run are far smaller than a provider's, so they're slower, make more mistakes, and lose track of long tasks sooner. Expect them to manage short, simple requests rather than real coding work. They also use a lot of memory and battery while they run.

## Turn it on

1. Open **Settings → Providers and models → Local models**.
2. Turn on **Run models on this phone**.
3. Under **Models**, tap **Download** next to a model.

When the download finishes, the model shows up in a chat's model list like any other, and you pick it the same way: tap the model chip under the message box. See [Choose a model for a chat](/docs/providers/#choose-a-model-for-a-chat).

## The models

| Model | Download | Notes |
| --- | --- | --- |
| **Qwen3 0.6B** | about 0.6 GB, or 1.2 GB on a MediaTek Dimensity 9500 | The smallest and fastest. Runs on almost any phone. |
| **Qwen3 1.7B** | about 1.8 GB | Better answers, slower. |
| **Qwen3 4B** | about 2.5 GB | Better answers again, and slower. Needs a phone with plenty of memory. |
| **Qwen3 8B** | about 5 GB | The largest. Only for phones with a lot of memory, such as 16 GB; on others, Android may stop it or other apps while it runs. |
| **Qwen3.5 2B** | about 1.3 GB | A newer design: most of its layers don't keep more memory as the conversation grows, so long chats need less than with Qwen3. |
| **Qwen3.5 4B** | about 2.7 GB | The larger Qwen3.5, with the same lighter memory use in long chats. |
| **Gemma 4 E2B** | 2 to 2.8 GB, depending on the phone | Google's small model. On a phone with the GPU driver it needs, it runs on the GPU. |
| **Gemma 4 E4B** | 3 to 3.7 GB, depending on the phone | Gemma 4 E2B's larger sibling, on the GPU when the phone has the driver, like it. |
| **Ministral 3 3B** | about 2.1 GB | Mistral's small model. It answers without thinking first. |

All except Ministral 3 3B can think before they answer, like larger models with reasoning. All of them are released under the Apache License 2.0; see [Model licenses](#model-licenses).

### Memory

A model takes about as much memory as its download while it runs, plus some for the conversation, on top of what Android and your other apps use. Pipkin loads a model even when that's more than is free: if the phone runs short, Android first closes apps you aren't using, then stops the model, and keeps Termux, where the agent runs. A stopped model loads again with your next message. Start with Qwen3 0.6B, and try the larger models on a phone with more memory, such as 12 GB or more.

## How Pipkin runs a model

Pipkin has two engines that run models: **LiteRT-LM**, Google's engine for phones, and **llama.cpp**. For each model it picks the fastest build your phone can run, among the builds that can hold the agent's instructions and tools, which take about 7,000 tokens before a chat even starts:

1. A build for the phone's NPU (its AI chip), which LiteRT-LM has for a few phones. Most of these hold too short a conversation for the agent, so Pipkin only picks one when no other build runs.
2. A build for the phone's GPU: LiteRT-LM's when the phone has the GPU driver it needs, otherwise llama.cpp's, which runs on Qualcomm's Adreno GPUs.
3. The CPU, the slowest, when nothing else runs.

Each model's row under **Models** says which engine it runs with, on which part of the phone, and how big its download is. If a GPU or NPU build fails to load on your phone, the chat says so, and Pipkin doesn't use that kind of build again until Android or Pipkin is updated. Send the message again to use another build; if it needs a different file, the model's row offers to download it.

## One model at a time

Your phone runs one model at a time. When a chat sends a message to a local model:

- If no model is loaded, Pipkin loads it first, which takes a few seconds to a minute.
- If another chat is using a model, this chat waits its turn. If the other chat uses a different model, Pipkin unloads it once that chat's reply is done, and loads this one.

While a chat waits or the model works, the line under the reply, beside its timer, shows what's happening: waiting for another chat, loading the model, reading your message and the conversation so far, or writing the reply and how fast.

The **Status** card under **Settings → Providers and models → Local models** shows which model is loaded, on which engine, and how much conversation it can hold. Tap **Unload** to give its memory back right away; a chat that's using it stops with an error. Pipkin also unloads a model that hasn't been used for five minutes; change that under **Minutes before an unused model unloads**.

A local model can hold a shorter conversation than a provider's: 32,768 tokens when your phone has the memory for the model and that much conversation, which Pipkin counts as at most half the phone's memory, and 16,384 otherwise. A model's reply can be up to an eighth of that. When a chat gets near the limit, the agent compacts it, summarising the older messages, as it does for any model; `/compact` does it on demand. With 16,384 tokens, most of which the agent's instructions and tools take, that happens every few messages.

## Downloads

Models are downloaded from [Hugging Face](https://huggingface.co), only when you tap **Download**. Pipkin checks every file against the checksum it expects and throws away one that doesn't match, so a model can't be swapped for something else on the way.

A download keeps going while you use other apps, with Pipkin's notification showing. If it's interrupted, for example when the phone loses its connection, tap **Try again** and it picks up where it stopped. **Cancel** stops a download and deletes what it got so far.

Before it starts, Pipkin checks there's room for the whole model, and leaves a little space free besides.

## Model licenses

Pipkin doesn't include any model; each one is downloaded from its publisher's Hugging Face repository, where its model card and license are:

| Model | Made by | Files from | License |
| --- | --- | --- | --- |
| Qwen3 0.6B, 1.7B, 4B, 8B | [Qwen](https://huggingface.co/Qwen) | [Qwen](https://huggingface.co/Qwen), and [LiteRT Community](https://huggingface.co/litert-community) for Qwen3 0.6B's LiteRT-LM builds | Apache License 2.0 |
| Qwen3.5 2B, 4B | [Qwen](https://huggingface.co/Qwen) | [Unsloth](https://huggingface.co/unsloth) | Apache License 2.0 |
| Gemma 4 E2B, E4B | [Google](https://huggingface.co/google) | [LiteRT Community](https://huggingface.co/litert-community), and [ggml](https://huggingface.co/ggml-org) for Gemma 4 E2B's llama.cpp build | Apache License 2.0 |
| Ministral 3 3B | [Mistral AI](https://huggingface.co/mistralai) | [Mistral AI](https://huggingface.co/mistralai) | Apache License 2.0 |

## Where the files are

Models are kept in Pipkin's private storage on the phone, not in Termux, so the agent and other apps can't read them. They aren't included in Android's backup to your Google account. To free the space, tap **Delete** next to a model. Uninstalling Pipkin deletes them too.

## Privacy

Once a model is downloaded, running it involves no network at all: your messages, files, and the model's replies stay on the phone. The agent reaches the model through a connection that only works on the phone itself and needs a key only Pipkin and the agent have, so other apps can't use it. See the [privacy policy](/docs/privacy/).
