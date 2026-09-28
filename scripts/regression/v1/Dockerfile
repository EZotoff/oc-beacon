# OpenCode V1 (1.18.32) — 与宿主 ~/oc-v1 安装方式同源（npm opencode-ai）
FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates git && rm -rf /var/lib/apt/lists/*
# 版本锚定：与宿主 systemd opencode-v1.service（1.18.32）完全一致
RUN npm i -g --registry=https://registry.npmmirror.com opencode-ai@1.18.32 \
    || npm i -g opencode-ai@1.18.32; opencode --version
EXPOSE 14199
CMD ["opencode", "serve", "--hostname", "0.0.0.0", "--port", "14199"]
