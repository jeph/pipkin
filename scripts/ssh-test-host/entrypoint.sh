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

if [ -n "${PIPKIN_AUTHORIZED_KEY:-}" ]; then
  printf '%s\n' "$PIPKIN_AUTHORIZED_KEY" >/home/pipkin/.ssh/authorized_keys
  chown pipkin:pipkin /home/pipkin/.ssh/authorized_keys
  chmod 600 /home/pipkin/.ssh/authorized_keys
fi

exec /usr/sbin/sshd -D -e
