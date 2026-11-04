#!/usr/bin/env bash
# Runs SSH servers in Docker (OrbStack on the Mac) for testing Pipkin's remote hosts on an emulator. The emulator
# reaches the Mac's localhost as 10.0.2.2, so a host published on port 2222 here is 10.0.2.2:2222 in Pipkin.
#
#   scripts/ssh-test-host.sh up [name] [port] [options]   start a host; options: --password, --no-bash, --key <file>
#   scripts/ssh-test-host.sh authorize [name] <key>      add a public key line to the pipkin user's authorized_keys
#   scripts/ssh-test-host.sh rekey [name]                recreate the container, giving it new host keys
#   scripts/ssh-test-host.sh pause|unpause [name]        freeze it, so connections hang instead of failing
#   scripts/ssh-test-host.sh stop|start [name]           stop it, so connections are refused
#   scripts/ssh-test-host.sh shell [name]                a shell in it as the pipkin user
#   scripts/ssh-test-host.sh down [name]                 remove it
#
# The name defaults to pipkin-ssh and the port to 2222. Every host has the user pipkin with the password pipkin;
# password sign-in is off unless --password is given.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
image=pipkin-ssh-test-host

usage() {
  sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 2
}

build() {
  docker build --quiet --tag "$image" "$here/ssh-test-host" >/dev/null
}

container_env() {
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$1" | grep -E '^PIPKIN_' || true
}

container_port() {
  docker port "$1" 22/tcp | head -n 1 | sed 's/.*://'
}

run() {
  local name=$1 port=$2
  shift 2
  local args=()
  local env
  for env in "$@"; do args+=(--env "$env"); done
  docker run --detach --name "$name" --hostname "$name" --publish "127.0.0.1:$port:22" "${args[@]}" "$image" >/dev/null
  echo "$name is up: ssh -p $port pipkin@localhost here, 10.0.2.2 port $port from the emulator"
}

cmd=${1:-}
[ -n "$cmd" ] || usage
shift

case "$cmd" in
  up)
    name=pipkin-ssh port=2222
    if [ $# -gt 0 ] && [[ $1 != --* ]]; then name=$1; shift; fi
    if [ $# -gt 0 ] && [[ $1 != --* ]]; then port=$1; shift; fi
    envs=()
    while [ $# -gt 0 ]; do
      case "$1" in
        --password) envs+=(PIPKIN_PASSWORD_AUTH=1) ;;
        --no-bash) envs+=(PIPKIN_NO_BASH=1) ;;
        --key) envs+=("PIPKIN_AUTHORIZED_KEY=$(cat "$2")"); shift ;;
        *) usage ;;
      esac
      shift
    done
    build
    docker rm --force "$name" >/dev/null 2>&1 || true
    run "$name" "$port" ${envs[@]+"${envs[@]}"}
    ;;
  authorize)
    name=pipkin-ssh
    [ $# -eq 2 ] && { name=$1; shift; }
    [ $# -eq 1 ] || usage
    docker exec -i -u pipkin "$name" sh -c 'cat >>~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys' <<<"$1"
    ;;
  rekey)
    name=${1:-pipkin-ssh}
    port=$(container_port "$name")
    envs=()
    while IFS= read -r env; do envs+=("$env"); done < <(container_env "$name")
    keys=$(docker exec -u pipkin "$name" cat /home/pipkin/.ssh/authorized_keys 2>/dev/null || true)
    docker rm --force "$name" >/dev/null
    run "$name" "$port" ${envs[@]+"${envs[@]}"}
    [ -z "$keys" ] || docker exec -i -u pipkin "$name" sh -c 'cat >~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys' <<<"$keys"
    ;;
  pause | unpause | stop | start)
    docker "$cmd" "${1:-pipkin-ssh}" >/dev/null
    ;;
  shell)
    docker exec -it -u pipkin -w /home/pipkin "${1:-pipkin-ssh}" bash
    ;;
  down)
    docker rm --force "${1:-pipkin-ssh}" >/dev/null
    ;;
  *) usage ;;
esac
