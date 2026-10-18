---
title: Run in the background
description: How Pipkin keeps chats and terminal commands running when you leave the app or lock the screen, and what Android can still stop.
---

The agent runs in Termux, a separate app from Pipkin. Android pauses and stops apps you aren't looking at to save battery and memory, so Pipkin takes a few steps to keep work going while you're away, and lets go of them as soon as nothing is running.

## While something is working

Pipkin counts as working while a chat is running, while the agent waits on a [background task](#background-tasks), while a command runs in a Pipkin terminal, and during sign-in, installing the agent, Termux setup, backups, and restores. For as long as any of these lasts, Pipkin:

- **Runs as a foreground service.** Android shows Pipkin's notification: **Pipkin agent is working**, **A background task is running** when only background tasks are, or **A terminal command is running** when only a terminal is busy. Android stops a foreground service far less readily than an app in the background.
- **Holds a wake lock.** This keeps the phone's processor running while the screen is off, so the agent keeps working, and so does Termux. The screen still turns off and locks as usual.

The notification's **Stop** button stops every working chat and every background task. The terminal notice has no Stop button, since stopping chats wouldn't stop a terminal's command. To stop the command, end it in the terminal or close the terminal.

A command that keeps running in a Pipkin terminal, such as a development server, counts as work for as long as it runs. That keeps the notification and the wake lock up the whole time, so close the terminal when you're done with it.

## Background tasks

The agent can run a command in the background, such as a development server, a long build, or a test suite, and stop working while it runs. When the command exits, its result comes back to the chat and the agent picks up where it left off, even if you've left the app.

- **The tasks show on the message box** as a **Background** tab, with each task's state and command.
- **A chat with background tasks keeps its agent** and counts toward the running chats limit, but isn't shown as working, so you can keep chatting.
- **Tasks end with their chat's agent.** Reloading or archiving the chat, switching it to another session, or Android stopping Termux ends them; Pipkin asks before a reload or archive does.
- **At most four tasks run at once in each chat.** Android stops apps that start too many processes, so each task runs as a single command.

The agent is told to use background tasks for anything that should keep running. It can still start processes another way, such as with `&`, `nohup`, `tmux`, or `screen`, but Pipkin can't see those or keep the phone awake for them, so Android may stop them. To turn background tasks off, turn off **Pipkin background** in **Settings → Pipkin extensions**.

## When nothing is working

Once every chat is idle, no background task is running, and no terminal is running a command, Pipkin removes its notification and releases the wake lock, so the phone can sleep normally. Pipkin doesn't hold a wake lock while idle, so leaving it open costs no extra battery.

Idle chats also give back their memory. A chat that isn't working, waiting on background tasks, or on screen stops its agent after the time set in **Settings → Advanced → Stop idle chats after** (5 minutes by default). Its history stays, and the agent starts again when you open the chat.

Pipkin only starts Termux while Pipkin is on screen, because Android can refuse to start another app from the background. Anything that needs a new agent, such as reopening a chat whose agent stopped, waits until you come back to Pipkin.

## Allow background activity

Battery optimization can still stop Pipkin or Termux, or cut off the agent's network, after a while in the background. Setup asks you to exempt both apps; you can do it later from **Settings → Permissions**:

- **Run in the background** exempts Pipkin. Tap **Allow** when Android asks.
- **Let Termux run in the background** exempts Termux. If Android can't ask directly, Pipkin opens Termux's battery settings, where you choose **Allow background usage → Unrestricted**.

Some phones name the setting differently:

| Phone | Setting |
| --- | --- |
| Samsung | **Battery → Unrestricted** |
| Xiaomi | **Battery saver → No restrictions** |
| OnePlus, Oppo, Realme | **Battery usage → Allow background activity** |

Also turn on **Notifications** in **Settings → Permissions**. Without them, Pipkin can't tell you when a chat finishes, stops, or [asks you something](/docs/extensions/#how-extensions-look-in-pipkin) while you're away.

## What Android can still stop

None of this makes Pipkin or Termux impossible to stop. Android can still end them in these cases:

- **The phone runs low on memory.** When memory gets tight, Android stops apps to free it, and it can stop Termux, with every chat and terminal in it, even while Pipkin's notification is showing. Background exemptions and the foreground service make this less likely, but nothing an app can do rules it out. Closing other heavy apps and running fewer chats at once help.
- **Too many processes are running.** On Android 12 and later, Android stops child processes once more than 32 are running across all apps. See [The 32-process limit](#the-32-process-limit) below.
- **You exit Termux.** Tapping **Exit** in Termux's notification stops everything running in Termux, including the agent. Leave Termux running while chats are working.

Commands you start yourself in the Termux app, outside Pipkin, don't count as Pipkin's work, so Pipkin's wake lock doesn't cover them. To keep one running with the screen off, run `termux-wake-lock` in Termux, or tap **Acquire wakelock** in Termux's notification, and release it with `termux-wake-unlock` when you're done.

### The 32-process limit

Android 12 added a limit on child processes: programs an app starts itself, rather than the app's own process. Everything the agent runs is one, because Termux starts it. Once more than 32 are running across all apps combined, Android stops the extra ones, oldest first, even with plenty of memory free. Android also stops child processes that use a lot of CPU while their app is in the background.

In Pipkin, these count toward the limit:

- **Each running chat**: a few processes for the agent, plus every command and tool it runs while it works.
- **Each open terminal**: its shell, plus whatever runs in it.
- **Anything else running in Termux**, such as commands you start in the Termux app, servers left running in the background, and other apps' child processes.

When a chat's agent is stopped this way, the chat shows **Agent stopped** (see [When a chat stops](#when-a-chat-stops)).

#### Stay under the limit

These work on any Android version and change nothing outside Pipkin:

- Lower **Settings → Advanced → Running chats**, which caps how many chats keep their agent running at once (6 by default, up to 16).
- Shorten **Settings → Advanced → Stop idle chats after**, so chats you've left stop their agent sooner. Their history stays.
- Close terminals you aren't using, and stop servers or watchers you started once you're done with them.

#### Turn the limit off

How to turn it off depends on the Android version. Each change below affects every app on the phone, not just Pipkin and Termux.

- **Android 14 and later**: in **Settings → System → Developer options**, turn on **Disable child process restrictions**. This also stops Android killing child processes for heavy CPU use. To show Developer options, tap **Build number** seven times; it's usually in **Settings → About phone**. If you turn Developer options off again, the restriction comes back.
- **Android 12L and 13**: there's no switch, but one `adb` command turns off both the limit and the CPU check, and the change persists across restarts:

  ```sh
  adb shell settings put global settings_enable_monitor_phantom_procs false
  ```

  To undo it, run `adb shell settings delete global settings_enable_monitor_phantom_procs`. This command also works on Android 14 and later, and keeps working if you later turn Developer options off.
- **Android 12**: only the 32-process cap can be raised, through `device_config`. On phones with Google Play services this also means turning off syncing of Android's device configuration, which can stop Android recovering from bad settings. [This guide](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md#commands-for-android-12) explains the commands and the risks.

#### Run adb on the phone itself

You don't need a computer for the `adb` commands. On Android 11 and later, Termux can connect to the phone's own wireless debugging:

1. Connect to Wi-Fi. In **Developer options**, turn on **Wireless debugging**.
2. In Termux, run `pkg install android-tools`.
3. In **Wireless debugging**, tap **Pair device with pairing code**. Use split screen or a floating window so the code stays visible. In Termux, run `adb pair localhost:<port>` with the port shown with the code, then enter the code.
4. Run `adb connect localhost:<port>` with the port on the main **Wireless debugging** screen, which differs from the pairing port.
5. Run the command for your Android version as shown above.

You can turn wireless debugging off again afterwards; the setting stays.

## When a chat stops

If the agent stops while a chat is working, the reply in progress ends there. Its history up to that point is saved.

- **The connection dropped.** Pipkin reconnects on its own and reloads the chat's history. If it drops again within a minute, Pipkin stops trying and shows **Agent stopped** instead.
- **The agent stopped.** The chat shows **Agent stopped** with a **Restart** button, which starts the agent again with the same chat. Then ask it to continue where it left off.

If you were away when it happened, Pipkin sends a notification: **Agent finished**, **Agent failed**, or **Pi exited**, with the chat's name. Tap it to open the chat.
