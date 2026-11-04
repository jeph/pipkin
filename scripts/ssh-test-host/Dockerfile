# An SSH server for testing Pipkin's remote hosts from an emulator: scripts/ssh-test-host.sh builds and runs it.
FROM debian:trixie-slim

RUN apt-get update \
  && apt-get install -y --no-install-recommends openssh-server bash git ripgrep file procps ca-certificates \
  && rm -rf /var/lib/apt/lists/* \
  && rm -f /etc/ssh/ssh_host_* \
  && mkdir -p /run/sshd \
  && useradd --create-home --shell /bin/bash pipkin \
  && echo 'pipkin:pipkin' | chpasswd \
  && install -d -m 700 -o pipkin -g pipkin /home/pipkin/.ssh

COPY sshd_config /etc/ssh/sshd_config
COPY entrypoint.sh /entrypoint.sh

EXPOSE 22
ENTRYPOINT ["/entrypoint.sh"]
