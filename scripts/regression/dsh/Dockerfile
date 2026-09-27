# DSH (DeepSeek Harness) 0.1.7-rc.2 — 与宿主 brew dsh 同版本（npm @deepseek-ai/dsh）
FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates git && rm -rf /var/lib/apt/lists/*
RUN npm i -g --registry=https://registry.npmmirror.com @deepseek-ai/dsh@0.1.7-rc.2 \
    || npm i -g @deepseek-ai/dsh@0.1.7-rc.2; dsh --version
EXPOSE 14200
# --host 0.0.0.0：容器内进程必须监听非 loopback 才能被 docker-proxy 命中
# （0.1.2+ CLI 若拒绝该 flag，由 setup-secrets.sh 生成的 profile patch 兜底）
CMD ["dsh", "web", "--no-open", "--host", "0.0.0.0", "--port", "14200"]
