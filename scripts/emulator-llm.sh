#!/usr/bin/env bash
# Signs Pipkin on an emulator in to the shared codex-lb provider, so agents can chat with a model while testing.
# Writes the provider into Pipkin's Pi config on the emulator's shared storage, the same models.json entry Pipkin's
# OpenAI-compatible provider screen saves, and makes its model the default. The key comes from the Mac's Keychain
# (service pipkin-codex-lb) and reaches the emulator through stdin, never a command line.
set -euo pipefail

readonly CONFIG_DIR=/sdcard/Pipkin/pi
readonly PROVIDER=codex-lb
readonly MODEL=gpt-6-astra

# Emulators only: the user's phone is never set up by script.
if [[ $# -ne 1 || $1 != emulator-* ]]; then
	echo "usage: $0 emulator-<port>" >&2
	exit 64
fi
serial=$1

CODEX_LB_API_KEY=$(security find-generic-password -s pipkin-codex-lb -w)
export CODEX_LB_API_KEY

# A config file on the emulator, or {} before Pipkin has written it.
read_config() {
	adb -s "$serial" shell "cat '$CONFIG_DIR/$1' 2>/dev/null || echo '{}'"
}

write_config() {
	printf '%s\n' "$2" | adb -s "$serial" shell "mkdir -p '$CONFIG_DIR' && cat > '$CONFIG_DIR/$1'"
}

# Read fully before writing: `cat >` truncates the file it would still be reading.
models=$(read_config models.json | jq --arg provider "$PROVIDER" --arg model "$MODEL" '
	.providers[$provider] = {
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
settings=$(read_config settings.json | jq --arg provider "$PROVIDER" --arg model "$MODEL" '.defaultProvider = $provider | .defaultModel = $model')
write_config models.json "$models"
write_config settings.json "$settings"
echo "Pipkin on $serial now defaults to $PROVIDER/$MODEL."
