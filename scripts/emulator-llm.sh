#!/usr/bin/env bash
# Signs Pipkin on an emulator in to the shared codex-lb provider, so agents can chat with a model while testing.
# Writes the provider into Pi's config folder in Termux ($HOME/.pipkin/pi), the same models.json entry Pipkin's
# OpenAI-compatible provider screen saves, and makes its model the default. adb can't reach Termux's home, so the
# merge runs in Termux: through RUN_COMMAND, sent as the debuggable Pipkin, for the F-Droid build, or through
# Pipkin's helper for the Play build. The key comes from the Mac's Keychain (service pipkin-codex-lb) and reaches the
# emulator through stdin, never a command line; it waits on shared storage only until the merge in Termux reads and
# deletes it.
set -euo pipefail

readonly PROVIDER=codex-lb
readonly MODEL=gpt-6-astra
readonly TERMUX_BIN=/data/data/com.termux/files/usr/bin
readonly TIMEOUT_SECONDS=30

# Emulators only: the user's phone is never set up by script.
if [[ $# -ne 1 || $1 != emulator-* ]]; then
	echo "usage: $0 emulator-<port>" >&2
	exit 64
fi
serial=$1
stage=/sdcard/Download/.pipkin-llm-$$

CODEX_LB_API_KEY=$(security find-generic-password -s pipkin-codex-lb -w)
export CODEX_LB_API_KEY

provider=$(jq -n --arg model "$MODEL" '{
	baseUrl: "https://codex.jeph.io/v1",
	api: "openai-responses",
	apiKey: env.CODEX_LB_API_KEY,
	models: [{
		id: $model,
		name: "GPT-6-Astra",
		reasoning: true,
		thinkingLevelMap: { off: null, minimal: null, low: "low", medium: "medium", high: "high", xhigh: "xhigh", max: "max" },
		input: ["text", "image"],
		contextWindow: 272000,
		maxTokens: 128000,
		cost: { input: 10, output: 50, cacheRead: 1, cacheWrite: 12.5 }
	}]
}')

# Runs in Termux: merges the staged provider into Pi's config, then deletes the staging folder and with it the key.
read -r -d '' merge <<'EOF' || true
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
const stage = dirname(process.argv[1]);
const patch = JSON.parse(readFileSync(join(stage, "patch.json"), "utf8"));
rmSync(stage, { recursive: true, force: true });
const dir = join(process.env.HOME, ".pipkin", "pi");
const read = (name) => {
	try {
		return JSON.parse(readFileSync(join(dir, name), "utf8"));
	} catch {
		return {};
	}
};
const models = read("models.json");
models.providers = { ...models.providers, [patch.id]: patch.provider };
const settings = { ...read("settings.json"), defaultProvider: patch.id, defaultModel: patch.model };
mkdirSync(dir, { recursive: true });
writeFileSync(join(dir, "models.json"), `${JSON.stringify(models, null, 2)}\n`, { mode: 0o600 });
writeFileSync(join(dir, "settings.json"), `${JSON.stringify(settings, null, 2)}\n`);
EOF

adb -s "$serial" shell "mkdir -p '$stage'"
printf '%s\n' "$merge" | adb -s "$serial" shell "cat > '$stage/merge.mjs'"
jq -n --arg id "$PROVIDER" --arg model "$MODEL" --argjson provider "$provider" '{id: $id, model: $model, provider: $provider}' |
	adb -s "$serial" shell "umask 077 && cat > '$stage/patch.json'"

# The Play build has no RUN_COMMAND: the merge goes through Pipkin's helper in Termux instead, reached over an adb
# forward with the pairing key Pipkin holds, using the helper bundle's own client mode.
run_through_helper() {
	local port local_port
	work=$(mktemp -d)
	trap 'rm -rf "$work"' EXIT
	(cd "$(dirname "$0")/../host" && OUT="$work" node build.ts)
	(umask 077 && adb -s "$serial" shell run-as io.jeph.pipkin cat no_backup/helper-pairing.json |
		jq -er '.current // .pending' >"$work/key") || {
		echo "Pipkin on $serial holds no helper pairing; is Pipkin's setup done?" >&2
		return 1
	}
	for port in 47310 47311 47312 47313 47314; do
		local_port=$(adb -s "$serial" forward tcp:0 "tcp:$port")
		if node "$work/pipkin-helper.mjs" client --port "$local_port" --key-file "$work/key" \
			-- -c "exec $TERMUX_BIN/node \"\$1\"" pipkin-llm "$stage/merge.mjs" </dev/null >/dev/null 2>"$work/error"; then
			adb -s "$serial" forward --remove "tcp:$local_port"
			return 0
		fi
		adb -s "$serial" forward --remove "tcp:$local_port"
	done
	cat "$work/error" >&2
	echo "No Pipkin helper on $serial ran the merge; open Termux and retry." >&2
	return 1
}

if adb -s "$serial" shell dumpsys package com.termux | grep -q 'versionName=googleplay\.'; then
	if ! run_through_helper; then
		adb -s "$serial" shell "rm -rf '$stage'"
		exit 1
	fi
else
	# Android lets an app start Termux's command service only while it is on screen.
	adb -s "$serial" shell am start -W -n io.jeph.pipkin/.MainActivity >/dev/null
	adb -s "$serial" shell "run-as io.jeph.pipkin am start-foreground-service --user 0 \
		-n com.termux/com.termux.app.RunCommandService -a com.termux.RUN_COMMAND \
		--es com.termux.RUN_COMMAND_PATH $TERMUX_BIN/node \
		--esa com.termux.RUN_COMMAND_ARGUMENTS '$stage/merge.mjs' \
		--ez com.termux.RUN_COMMAND_BACKGROUND true" >/dev/null
fi

for ((waited = 0; waited < TIMEOUT_SECONDS; waited++)); do
	if ! adb -s "$serial" shell "[ -e '$stage' ]"; then
		echo "Pipkin on $serial now defaults to $PROVIDER/$MODEL."
		exit 0
	fi
	sleep 1
done
adb -s "$serial" shell "rm -rf '$stage'"
echo "Termux did not run the merge within ${TIMEOUT_SECONDS}s; is Pipkin's setup in Termux done?" >&2
exit 1
