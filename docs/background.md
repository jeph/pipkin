---
title: Run in the background
description: How Pipkin keeps chats and terminal commands running when you leave the app or lock the screen, and what Android can still stop.
---

Android pauses and stops background apps to save battery and memory. The agent runs in Termux, a separate app, so Pipkin keeps both running while work is in progress.

## While something is working

Pipkin is working while a chat runs, a terminal runs a command, or sign-in, agent install, Termux setup, a backup, or a restore is in progress. During that time Pipkin:

- **Runs as a foreground service**, showing **Pipkin agent is working**, or **A terminal command is running** if only a terminal is busy. Android rarely stops foreground services.
- **Holds a wake lock**, which keeps the processor, and so Pipkin and Termux, running with the screen off.

**Stop** in the notification stops every working chat. The terminal notification has no Stop; end the command or close the terminal.

A long-running terminal command, such as a development server, keeps the notification and wake lock up until it ends. Close the terminal when you're done.

## When nothing is working

When no chat or terminal is working, Pipkin removes its notification and releases the wake lock.

A chat that is idle and off screen stops its agent after **Settings → Advanced → Stop idle chats after** (5 minutes by default). Its history stays, and the agent restarts when you open the chat.

Android can block starting Termux from the background, so Pipkin starts agents only while it's on screen.

## Allow background activity

Battery optimization can stop Pipkin or Termux, or cut their network, in the background. Setup asks you to exempt both; you can also do it in **Settings → Permissions**:

- **Run in the background** exempts Pipkin. Tap **Allow** when Android asks.
- **Let Termux run in the background** exempts Termux. If Android can't ask, Pipkin opens Termux's battery settings; choose **Allow background usage → Unrestricted**.

Some phones name the setting differently:

| Phone | Setting |
| --- | --- |
| Samsung | **Battery → Unrestricted** |
| Xiaomi | **Battery saver → No restrictions** |
| OnePlus, Oppo, Realme | **Battery usage → Allow background activity** |

Turn on **Notifications** too, so Pipkin can tell you when a chat finishes, stops, or [asks you something](/docs/extensions/#how-extensions-look-in-pipkin).

## What Android can still stop

- **Low memory.** Android stops apps to free memory, and can stop Termux with every chat and terminal in it, even during work. Close heavy apps and run fewer chats.
- **Too many processes.** On Android 12 and later, Android stops child processes past 32 across all apps. See [The 32-process limit](#the-32-process-limit).
- **Exiting Termux.** **Exit** in Termux's notification stops everything in Termux, including the agent.

Pipkin's wake lock doesn't cover commands you start in the Termux app. To keep one running with the screen off, run `termux-wake-lock`, or tap **Acquire wakelock** in Termux's notification. Release it with `termux-wake-unlock`.

### The 32-process limit

Since Android 12, Android limits child processes, the programs apps start. Everything the agent runs is a child process of Termux. When more than 32 run across all apps, Android stops the oldest, regardless of free memory. It also stops child processes that use heavy CPU while their app is in the background.

In Pipkin, these count toward the limit:

- **Each running chat**: a few processes for the agent, plus the commands and tools it runs.
- **Each open terminal**: its shell and whatever runs in it.
- **Everything else in Termux**, and other apps' child processes.

A chat whose agent is stopped shows **Agent stopped** (see [When a chat stops](#when-a-chat-stops)).

#### Stay under the limit

- Lower **Settings → Advanced → Running chats**, the number of chats that keep their agent running (6 by default, up to 16).
- Shorten **Settings → Advanced → Stop idle chats after**.
- Close unused terminals, and stop servers and watchers when you're done.

#### Turn the limit off

These changes apply to every app on the phone.

- **Android 14 and later**: turn on **Settings → System → Developer options → Disable child process restrictions**. This also turns off the CPU check. To show Developer options, tap **Build number** seven times, usually in **Settings → About phone**. Turning Developer options off turns the restriction back on.
- **Android 12L and 13**: run this `adb` command, which turns off the limit and the CPU check and persists across restarts:

  ```sh
  adb shell settings put global settings_enable_monitor_phantom_procs false
  ```

  To undo it, run `adb shell settings delete global settings_enable_monitor_phantom_procs`. The command also works on Android 14 and later, regardless of Developer options.
- **Android 12**: only the 32-process cap can be raised, through `device_config`. On phones with Google Play services, this requires disabling device config sync, which can stop Android recovering from bad settings. [This guide](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md#commands-for-android-12) covers the commands and risks.

#### Run adb on the phone

On Android 11 and later, Termux can run `adb` against the phone itself over wireless debugging:

1. Connect to Wi-Fi and turn on **Developer options → Wireless debugging**.
2. In Termux, run `pkg install android-tools`.
3. In **Wireless debugging**, tap **Pair device with pairing code**. With the code visible in split screen or a floating window, run `adb pair localhost:<port>` in Termux using the port shown with the code, then enter the code.
4. Run `adb connect localhost:<port>` using the port on the main **Wireless debugging** screen.
5. Run the command for your Android version.

You can turn wireless debugging off afterward; the setting persists.

## When a chat stops

If the agent stops mid-reply, the reply ends there. History up to that point is saved.

- **Connection dropped**: Pipkin reconnects and reloads the history. If it drops again within a minute, Pipkin shows **Agent stopped**.
- **Agent stopped**: tap **Restart** to start the agent again in the same chat, then ask it to continue.

If you were away, Pipkin sends **Agent finished**, **Agent failed**, or **Pi exited** with the chat's name. Tap it to open the chat.
