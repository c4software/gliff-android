# The demonstration server: sshd in front of server.py, nothing else.
#
#   docker build -t gliff-demo store/demo-server
#   docker run -d --name gliff-demo --restart unless-stopped \
#     -p 2222:2222 -v gliff-demo-keys:/keys --read-only --tmpfs /run --tmpfs /tmp \
#     --cap-drop ALL --cap-add SETUID --cap-add SETGID --cap-add SYS_CHROOT \
#     --memory 256m --pids-limit 100 gliff-demo
FROM alpine:3.22

RUN apk add --no-cache openssh-server openssh-keygen python3 \
 && adduser -D -s /bin/sh demo \
 # An account without a password, but not a locked one: sshd refuses those.
 && sed -i 's/^demo:!/demo:*/' /etc/shadow \
 && mkdir /keys /opt/demo

COPY sshd_config /etc/ssh/sshd_config
COPY any-key /usr/local/bin/any-key
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY server.py sample.h264 /opt/demo/
RUN chmod 755 /usr/local/bin/any-key /usr/local/bin/entrypoint.sh /opt/demo/server.py

VOLUME /keys
EXPOSE 2222
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
