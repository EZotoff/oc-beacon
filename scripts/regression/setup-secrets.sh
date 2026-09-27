#!/usr/bin/env bash
# setup-secrets.sh — 生成 runtime/ 运行时文件（不入库；密钥只从宿主既有配置提取，不回写仓库）
set -euo pipefail
cd "$(dirname "$0")"

# ---- 密钥来源（均为宿主已在用的测试凭据，仅在本地容器内复用）----
V1CONF=$HOME/oc-v1-env/config/opencode/opencode.jsonc
[ -f "$V1CONF" ] || V1CONF=$HOME/oc-v1-env/config/opencode/opencode.json
ZHIPU_KEY=$(python3 -c "import json,re,sys; txt=open('$V1CONF').read(); txt=re.sub(r'(?m)^\\s*//.*$','',txt); txt=re.sub(r'\\s//[^\"]*$','',txt); d=json.loads(txt); print(d['provider']['zhipuai']['options']['apiKey'])")

mkdir -p runtime/{v1-config,v1-data,v2-config,v2-data,dsh-home,workspace}
W=$(cd runtime/workspace && pwd)

# workspace：会话工作目录（git 仓库使 /vcs* 端点有料可报）
if [ ! -d "$W/.git" ]; then
  git -C "$W" init -q && echo "# regression workspace" > "$W/README.md" &&
  git -C "$W" add -A && git -C "$W" -c user.email=reg@local -c user.name=reg commit -qm init
fi

# ---- V1：provider 配置（zhipuai glm-5.3，与 ~/oc-v1-env 同配方）----
cat > runtime/v1-config/opencode.json <<EOF
{
  "provider": {
    "zhipuai": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "zhipuai",
      "options": { "baseURL": "https://open.bigmodel.cn/api/coding/paas/v4", "apiKey": "$ZHIPU_KEY" },
      "models": { "glm-5.3": {}, "glm-5.3-flash": {} }
    }
  }
}
EOF

# ---- V2：自定义 provider（V2 无 auth.json 时用 config 内联 apiKey；{env:} 展开为 V2 官方特性）----
cat > runtime/v2-config/opencode.json <<EOF
{
  "provider": {
    "zhipuai": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "zhipuai",
      "options": { "baseURL": "https://open.bigmodel.cn/api/coding/paas/v4", "apiKey": "{env:ZHIPU_API_KEY}" },
      "models": { "glm-5.3": {}, "glm-5.3-flash": {} }
    }
  }
}
EOF
cat > .env <<EOF
V1_PASSWORD=regression-v1-pw
V2_PASSWORD=regression-v2-pw
ZHIPU_API_KEY=$ZHIPU_KEY
EOF
chmod 600 .env
# 自定义 provider 的 npm SDK（V1/V2 都需要本地可解析的 @ai-sdk/openai-compatible）
ln -sfn $HOME/oc-v1-env/config/opencode/node_modules runtime/v1-config/node_modules
ln -sfn $HOME/oc-v1-env/config/opencode/node_modules runtime/v2-config/node_modules

# ---- DSH：settings（zai-coding-cn glm-5.3，与宿主 ~/.dsh/settings.yaml.imported 同配方）----
mkdir -p runtime/dsh-home
cat > runtime/dsh-home/settings.yaml <<'EOF'
llm-pi-ai:
  providers:
    zai-coding-cn:
      models:
        - id: glm-5.3
          name: GLM-5.3
          contextWindow: 1000000
          maxTokens: 131072
        - id: glm-5.3-flash
          name: GLM-5.3-Flash
          contextWindow: 1000000
          maxTokens: 131072
agent-default-model:
  provider: zai-coding-cn
  model: glm-5.3
EOF
# 凭据：直接只读复制宿主 .credentials.yaml（含 zai key refs；容器内与宿主同源）
cp "$HOME/.dsh/.credentials.yaml" runtime/dsh-home/.credentials.yaml
chmod 600 runtime/dsh-home/.credentials.yaml

echo "OK: runtime/ 已生成（含 workspace git 仓库、三面配置、.env）"
