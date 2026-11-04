#!/bin/sh
# Starts sshd in the foreground. Host keys are made on first start, so rebuilding the container gives it new ones,
# which is how the "host key changed" case is tested.
set -eu

ssh-keygen -A >/dev/null

if [ "${PIPKIN_PASSWORD_AUTH:-0}" = 1 ]; then
  sed -i 's/^PasswordAuthentication no$/PasswordAuthentication yes/' /etc/ssh/sshd_config
fi

# A host without bash, to check that Pipkin says bash is missing instead of failing obscurely.
if [ "${PIPKIN_NO_BASH:-0}" = 1 ]; then
  usermod --shell /bin/sh pipkin
  rm -f /bin/bash /usr/bin/bash
fi

# Added only when missing, since `start` runs this again and keys added with `authorize` must survive it.
keys=/home/pipkin/.ssh/authorized_keys
if [ -n "${PIPKIN_AUTHORIZED_KEY:-}" ] && ! grep -qxF "$PIPKIN_AUTHORIZED_KEY" "$keys" 2>/dev/null; then
  printf '%s\n' "$PIPKIN_AUTHORIZED_KEY" >>"$keys"
  chown pipkin:pipkin "$keys"
  chmod 600 "$keys"
fi

exec /usr/sbin/sshd -D -e
