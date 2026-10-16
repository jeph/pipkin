#!/usr/bin/env bash
# Signs Pipkin on an emulator in to the shared codex-lb provider, so agents can chat with a model while testing.
# Writes the provider into Pi's config folder in Termux ($HOME/.pipkin/pi), the same models.json entry Pipkin's
# OpenAI-compatible provider screen saves, and makes its model the default. adb can't reach Termux's home, so the
# merge runs in Termux through RUN_COMMAND, sent as the debuggable Pipkin, which holds that permission. The key comes
# from the Mac's Keychain (service pipkin-codex-lb) and reaches the emulator through stdin, never a command line; it
# waits on shared storage only until the merge in Termux reads and deletes it.
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

# Android lets an app start Termux's command service only while it is on screen.
adb -s "$serial" shell am start -W -n io.jeph.pipkin/.MainActivity >/dev/null
adb -s "$serial" shell "run-as io.jeph.pipkin am start-foreground-service --user 0 \
	-n com.termux/com.termux.app.RunCommandService -a com.termux.RUN_COMMAND \
	--es com.termux.RUN_COMMAND_PATH $TERMUX_BIN/node \
	--esa com.termux.RUN_COMMAND_ARGUMENTS '$stage/merge.mjs' \
	--ez com.termux.RUN_COMMAND_BACKGROUND true" >/dev/null

for ((waited = 0; waited < TIMEOUT_SECONDS; waited++)); do
	if ! adb -s "$serial" shell "[ -e '$stage' ]"; then
		echo "Pipkin on $serial now defaults to $PROVIDER/$MODEL."
		exit 0
	fi
	sleep 1
done
adb -s "$serial" shell "rm -rf '$stage'"
echo "Termux did not run the merge within ${TIMEOUT_SECONDS}s; is allow-external-apps set and Pipkin's setup done?" >&2
exit 1
