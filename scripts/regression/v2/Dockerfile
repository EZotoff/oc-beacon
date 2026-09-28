# OpenCode V2 (2.0.18) — 宿主 systemd opencode-v2.service 同版本；二进制经 compose 只读挂载
# （/usr/local/bin/opencode ← ~/.opencode/bin/opencode，glibc ELF 203MB）
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates git && rm -rf /var/lib/apt/lists/*
# 占位：真实二进制由 volume 覆盖；此 stub 保证镜像独立可构建
RUN printf '#!/bin/sh
exec /usr/local/bin/opencode.real "$@"\n' > /usr/local/bin/entry.sh && chmod +x /usr/local/bin/entry.sh || true
EXPOSE 14096
CMD ["opencode", "serve", "--hostname", "0.0.0.0", "--port", "14096"]
