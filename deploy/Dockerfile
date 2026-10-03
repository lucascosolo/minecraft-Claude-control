FROM eclipse-temurin:25-jdk-noble

ARG UID=1000
ARG GID=1000

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl git sudo rsync acl jq ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# mc runs the server with the host owner's ids; agent runs the Claude CLI and owns nothing outside its own directories.
# The base image's "ubuntu" user holds uid/gid 1000, so it goes first.
RUN (userdel -r ubuntu 2>/dev/null || true) && (groupdel ubuntu 2>/dev/null || true) \
 && groupadd -g "$GID" mc && useradd -m -u "$UID" -g mc -s /bin/bash mc \
 && useradd -m -U -s /bin/bash agent && usermod -aG agent mc \
 && useradd -m -U -s /bin/bash repair

USER agent
RUN curl -fsSL https://claude.ai/install.sh | bash
USER root
RUN ln -s /home/agent/.local/bin/claude /usr/local/bin/claude

# mc may run exactly one program as agent: the claude binary, keeping only the two Claude variables.
RUN printf '%s\n' \
      'Defaults:mc env_keep += "CLAUDE_CODE_OAUTH_TOKEN CLAUDE_CONFIG_DIR JAVA_HOME"' \
      'Defaults:mc secure_path="/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"' \
      'Defaults:mc umask=0007, umask_override, !use_pty' \
      'mc ALL=(agent) NOPASSWD: /usr/local/bin/claude' > /etc/sudoers.d/claude \
 && chmod 440 /etc/sudoers.d/claude && visudo -c

# Repair mode (docs/PRD-v0.3.md N1/N4): the repair user runs only the claude binary, in a private copy under its home.
# It has no sudo rule; the start script applies its changes and requests as mc.

COPY entrypoint.sh boot-guard.sh permissions.sh library.sh superseded.sh launch.sh repair.sh claude-repair /usr/local/bin/
COPY repair-prompt.md /usr/local/share/claude-repair/prompt.md
RUN cd /usr/local/bin && chmod 755 entrypoint.sh boot-guard.sh permissions.sh library.sh superseded.sh launch.sh repair.sh claude-repair \
 && chmod 644 /usr/local/share/claude-repair/prompt.md
WORKDIR /server
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
