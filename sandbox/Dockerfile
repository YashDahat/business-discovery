# Cline execution sandbox — a long-lived, per-brief workspace container the master spawns and drives
# via `docker exec` (SandboxManager/SandboxExecService). It runs NO batch job: the entrypoint just
# keeps it alive so Cline can write/edit files and run scripts against the cloned repo in /workspace.
#
# Toolchains: Java 17 + Maven and Node 20 (to build the generated Spring+React project) PLUS
# Python 3 and TypeScript (tsx) for Cline-authored scripts. Runs as a non-root user; the master
# injects only GITHUB_TOKEN + repo url/branch — never DB creds or LLM keys.
FROM eclipse-temurin:17-jdk-jammy

# Toolchains + a full everyday-CLI toolkit so `run_command` behaves like a normal Linux shell.
# (No docker CLI — the sandbox never spawns sibling containers.)
RUN apt-get update && apt-get install -y --no-install-recommends \
        # shell + build toolchains
        bash \
        git \
        ca-certificates \
        python3 \
        python3-pip \
        python3-venv \
        # file/text: viewing, editing, searching, archiving
        less \
        vim \
        nano \
        grep \
        sed \
        gawk \
        findutils \
        tree \
        jq \
        unzip \
        zip \
        # networking: connectivity, interfaces, transfers, remote
        curl \
        wget \
        iputils-ping \
        iproute2 \
        net-tools \
        dnsutils \
        openssh-client \
        # system: processes, resources, privilege, docs
        procps \
        htop \
        sudo \
        man-db \
        bash-completion \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && apt-get install -y --no-install-recommends maven \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && npm install -g tsx typescript \
    && rm -rf /var/lib/apt/lists/*

# Non-root user owning the workspace, with passwordless sudo *inside this isolated container* so Cline
# can install extra packages if a task needs them (the container has no host mounts and is ephemeral).
RUN useradd -ms /bin/bash cline \
    && mkdir -p /workspace \
    && chown -R cline:cline /workspace \
    && echo "cline ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/cline \
    && chmod 0440 /etc/sudoers.d/cline

USER cline

# Git identity (required for git commit inside the sandbox).
RUN git config --global user.email "cline@discovery.local" \
    && git config --global user.name "Cline" \
    && git config --global init.defaultBranch main

WORKDIR /workspace

# Keep-alive: the master execs into this container; it runs no foreground job of its own.
CMD ["sleep", "infinity"]
