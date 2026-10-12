#!/bin/sh
# The host key lives in a volume: a new one at each start would look to the
# app like another machine answering at the same address.
set -e
[ -f /keys/ssh_host_ed25519_key ] || ssh-keygen -q -t ed25519 -N '' -f /keys/ssh_host_ed25519_key
echo "host key: $(ssh-keygen -lf /keys/ssh_host_ed25519_key.pub)"
exec /usr/sbin/sshd -D -e -f /etc/ssh/sshd_config
