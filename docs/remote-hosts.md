---
title: Remote hosts
description: Work on a project on another computer over SSH, while the agent keeps running on your phone.
---

A remote host is a computer of your own, such as a desktop, a home server, or a cloud machine, that Pipkin reaches over SSH. A workspace can be a folder on one: the agent keeps running on your phone, while its tools, the file explorer, git, and terminals work on the host.

Nothing is installed on the host. It needs an SSH server and `bash`, which most Linux and macOS machines already have, and `git` if you want the git pane.

## Add a host

1. Open **Settings → Remote hosts** and tap **Add host**. The first time, Pipkin offers to install OpenSSH in Termux; tap **Install OpenSSH** and wait for it to finish.
2. Give the host a **Name**, which is how the sidebar and chats refer to it, such as `devbox`.
3. Enter its **Address** as `user@host`, for example `me@devbox.lan`, `me@192.168.1.20`, or `me@[fd00::1]`, and its **Port** if it isn't 22.
4. Choose how to **Sign in with**:
   - **Pipkin's key.** Pipkin makes an SSH key for itself the first time you need it. Tap **Copy public key** and add that line to `~/.ssh/authorized_keys` on the host.
   - **Password.** Pipkin asks for the password each time it connects, and doesn't save it.
   - **Use my ~/.ssh/config.** For a host you've already set up in Termux's own `~/.ssh/config`, with your own keys or jump hosts. The address is then the `Host` name from that file.
5. Tap **Connect** to check that it works, or **Save**.

### The host's key

The first time Pipkin connects to a host, it shows the host's key fingerprint and asks whether to trust it. Check that it matches the host's own, for example by running `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the host, then tap **Trust and connect**.

If the key a host sends later doesn't match the one you trusted, Pipkin refuses to connect and warns you: someone may be intercepting the connection. When you know why the key changed, for example because the server was reinstalled, open the host in **Settings → Remote hosts**, tap **Forget host key**, and connect again to check the new one.

## Open a folder on a host

1. Tap **Add workspace** in the sidebar.
2. Tap the machine row under the search field, which reads **This phone** at first, and pick the host. You can also add a host from that menu.
3. Browse to the folder, or type its path, and tap **Add as workspace**.

The workspace shows in the sidebar beside your other workspaces, with a server badge, and the host's name on its own line between the folder's name and its path. Chats you start in it work on that folder.

## What runs where

| On the host | On the phone |
| --- | --- |
| The agent's `read`, `write`, `edit`, and `bash` tools, and `!` commands | The agent itself, and what it sends to your model provider |
| Background tasks | The output of background tasks and of long `bash` commands, which the agent can still read |
| The file explorer, git, and terminals | Your chats and their history |
| Files you attach, which are uploaded to `~/.cache/pipkin/attachments` on the host | Photos you attach, which go to the model with your message |

A few things work differently in a remote chat:

- The agent searches with `bash`, using `grep`, `rg`, or `find` on the host. Pi's own `grep`, `find`, and `ls` tools are off in remote chats, since they would search the phone.
- Pi doesn't load the project's `AGENTS.md` or `CLAUDE.md` from the host. The agent can still read them when it needs to, and your own instructions in Pi's configuration on the phone still apply.
- [Extensions](/docs/extensions/) you've installed run on the phone. An extension's own tools work on the phone's files, not the host's.

## Connections

Pipkin keeps one connection per host and shares it between chats, the file explorer, git, and terminals. It closes 10 minutes after anything last used it, and a remote chat on screen counts as using it. A host that signs in with Pipkin's key connects again on its own when it's next needed, and so does one from your `~/.ssh/config` whose key needs no passphrase. A host that asks for a password or passphrase has to be connected again, and the chat, picker, or terminal offers **Connect**.

While a remote chat is on screen, Pipkin checks every few seconds that its host still answers. If the network drops or the host stops answering, a warning floats over the top of the chat within about 15 seconds, and you can't send a message until the host can be reached again; anything you type stays in the message box. A terminal ends with the reason and **Restart shell**. **Settings → Remote hosts** shows which hosts are connected, and **Disconnect** closes a host's connection.

## Make a new key

Pipkin uses one key for every host that signs in with Pipkin's key. **Settings → Remote hosts → Pipkin's key** shows its name, its fingerprint, and the hosts that use it, with **Copy public key**.

If someone else may have a copy of the key, tap **Make a new key**. Pipkin deletes the old key on the phone, makes a new one, and closes the connections of the hosts that used it. Those hosts refuse Pipkin until you replace the old Pipkin line in their `~/.ssh/authorized_keys` with the new one, which Pipkin shows you to copy. Pipkin doesn't change `authorized_keys` itself, so remove the old line on each host to stop the old key working there.

## Remove a host

Open the host in **Settings → Remote hosts** and tap **Remove host**. Its workspaces leave the sidebar and their chats are archived, the way removing a workspace does, and anything running in them stops. Nothing on the host changes.

## Where Pipkin keeps it

Pipkin keeps what it needs to reach your hosts in Termux, in `~/.pipkin/ssh`:

| Path | Holds |
| --- | --- |
| `hosts.json` | The hosts you've added |
| `config` | The OpenSSH configuration Pipkin writes from them. Edits here are overwritten; put your own settings in `~/.ssh/config`, which it includes. |
| `id_ed25519`, `id_ed25519.pub` | Pipkin's key |
| `known_hosts` | The host keys you've trusted in Pipkin. Termux's `~/.ssh/known_hosts` is read too, but never changed. |

None of these are in [backups](/docs/configure/), so a backup you share doesn't carry the key. Each remote workspace also has a small folder in `~/.pipkin/remotes` that Pi runs in on the phone, which is how saved chats remember their host and folder.
