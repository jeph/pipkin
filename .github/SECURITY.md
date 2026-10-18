# Security Policy

## Supported versions

Pipkin is in early development and has no published releases yet. Only the latest commit on the
`dev` branch receives security fixes.

| Version | Security updates |
| --- | --- |
| Latest `dev` commit | Supported |
| Older commits | Not supported |

Before reporting a problem, reproduce it with a build of the latest `dev` commit when it is safe to
do so.

## Reporting a vulnerability

Do not disclose suspected vulnerabilities in a public issue, discussion, pull request, or social
media post. Submit a
[private vulnerability report](https://github.com/jeph/pipkin/security/advisories/new) instead.

Include enough information to investigate without exposing private data:

- The Pipkin commit, Android version, phone model, and Termux build (F-Droid or Google Play) and
  version.
- The security impact and who could exploit it.
- Minimal reproduction steps or a proof of concept.
- Sanitized logs or screenshots, if relevant.
- Any suggested mitigation or fix.

Never include API keys, subscription sign-in tokens, Pipkin's pairing key, the contents of Pi's
configuration or Pipkin's backups, chat transcripts, private repository contents, or other personal
data. The maintainer will not ask you to provide authentication secrets.

Reports will be assessed privately. If the report is accepted, the maintainer will coordinate a fix
and public disclosure as appropriate. Please allow time to investigate before publishing details.
Reporters can be credited in a published advisory if they want attribution.

## In scope

Examples of security issues in Pipkin include:

- A way for another app on the phone to run commands in Termux, connect to Pipkin's helper, or
  impersonate Pipkin or the helper to each other.
- Pipkin accepting a Termux build that isn't signed by F-Droid or Google Play.
- Exposure or insecure storage of API keys, subscription sign-ins, the pairing key, or backups.
- Secrets or chat contents appearing in notifications, logs, or files readable by other apps.
- Pipkin running a repository's git configuration, hooks, or other code before the user chooses to
  trust that repository.
- A file path, file name, or message from the agent or a repository that makes Pipkin read, write,
  or delete files outside what was requested.
- Release artifact, signing, or packaging issues that could allow code substitution.

## Out of scope

The following are not vulnerabilities in this project by themselves:

- Commands the agent runs, or changes it makes, as the model decides. Pipkin runs a coding agent
  with the user's permissions in Termux by design.
- Prompt injection from files, web pages, or tool output that leads the agent to take an action,
  unless Pipkin itself bypasses a confirmation or safety control.
- Behavior of Pi extensions, skills, or packages the user installs.
- Model provider availability, rate limits, account policies, or responses.
- Social engineering or phishing that does not exploit Pipkin.
- Vulnerabilities that require root access, a compromised Android or Termux install, or a user
  intentionally giving an attacker access to an unlocked phone.

Vulnerabilities in Termux, Pi, Android, or a model provider are outside this project's control and
should be reported through their own security processes. You may also notify this project privately
when a Pipkin-side mitigation could protect its users.

## Safe research

- Test only on devices, accounts, and data you own or have explicit permission to use.
- Prefer an Android emulator and a throwaway Termux install over your everyday phone.
- Do not access, retain, or disclose another person's data.
- Do not disrupt model providers, this repository, or other users.
- Stop testing if you encounter data or access outside your authorization.
