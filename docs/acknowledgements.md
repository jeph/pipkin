---
title: Acknowledgements
description: The projects that inspired Pipkin and the open-source software it's built on.
---

Pipkin stands on the work of many open-source projects. Thank you to everyone who builds and maintains them.

The license texts for everything Pipkin ships are in the app under **Settings → About → Open-source licenses**, and the website's are at [/licenses.txt](/licenses.txt).

## Inspiration

- **[Paseo](https://paseo.sh)**, an open-source app for running coding agents from your desktop and phone. Pipkin's interface is modelled on Paseo's, and some of its code is adapted from [Paseo's](https://github.com/getpaseo/paseo): the folder search, the file-type icon table and icon colours, the layout and type scale, and the syntax highlighting colours.

## The agent

- **[Pi](https://pi.dev)** is the agent harness that does the work. Pipkin installs it in Termux and is compatible with its skills, prompt templates, and extensions.
- **[Termux](https://termux.dev)** is the Linux environment on Android where the agent, your files, and your tools live.

## The app

- **[Jetpack Compose and AndroidX](https://developer.android.com/jetpack/androidx)** for the interface, written in **[Kotlin](https://kotlinlang.org)** with [kotlinx.coroutines, kotlinx.serialization, and kotlinx.collections.immutable](https://github.com/Kotlin).
- **[Multiplatform Markdown Renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)** and **[JetBrains Markdown](https://github.com/JetBrains/markdown)** render replies.
- **[libghostty-android](https://github.com/SagerNet/libghostty-android)** is the terminal. It's built on **[Ghostty](https://github.com/ghostty-org/ghostty)**, with the [Zig](https://codeberg.org/ziglang/zig) standard library, [uucode](https://github.com/jacobsandlund/uucode), [Wuffs](https://github.com/google/wuffs), and the [Unicode Character Database](https://www.unicode.org).
- **[tree-sitter](https://github.com/tree-sitter/tree-sitter)** highlights code, with grammars from the tree-sitter project and its community for Bash, C, C#, C++, CSS, Dart, Dockerfile, Go, INI, Java, JavaScript, JSON, Kotlin, Lua, Make, Perl, PHP, Python, Ruby, Rust, Swift, TOML, TypeScript, XML, and YAML.
- **[Lucide](https://lucide.dev)** provides the interface icons, **[Material Icon Theme](https://github.com/material-extensions/vscode-material-icon-theme)** the file-type icons, and **[Lobe Icons](https://github.com/lobehub/lobe-icons)** the provider logos.
- **[JetBrains Mono](https://github.com/JetBrains/JetBrainsMono)** is the code font.

## The bundled extensions and host

- **[pi-web-access](https://github.com/nicobailon/pi-web-access)**: Pipkin's web search and page fetching extension is a modified copy. It uses [Mozilla Readability](https://github.com/mozilla/readability), [Defuddle](https://github.com/kepano/defuddle), [linkedom](https://github.com/WebReflection/linkedom), [Turndown](https://github.com/mixmark-io/turndown), [undici](https://github.com/nodejs/undici), [unpdf](https://github.com/unjs/unpdf), the [MCP TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk), and [p-limit](https://github.com/sindresorhus/p-limit).
- **[rpiv-todo](https://github.com/juicesharp/rpiv-mono)**: the to-do list extension's tool texts come from it.
- **[jsdiff](https://github.com/kpdecker/jsdiff)** parses diffs, and **[TypeBox](https://github.com/sinclairzx81/typebox)** builds the extensions' tool schemas.

## The website

- **[Astro](https://astro.build)** and **[Starlight](https://starlight.astro.build)** build this site and guide, with [Expressive Code](https://expressive-code.com) for code blocks and [Pagefind](https://pagefind.app) for search.
