# 三面全量回归套件 — OC Beacon

对 app 支持的三种服务端面（OpenCode V1 / OpenCode V2 / DSH）做可重复的 API 合同层回归。

## 组成

| 文件 | 用途 |
|---|---|
| `docker-compose.yml` | 三面容器编排（V1=14199 / V2=14096 / DSH=14200，与宿主 systemd v1@4199 / v2@4096 完全错开） |
| `v1/Dockerfile` | OpenCode 1.18.32（npm `opencode-ai@1.18.32`，与宿主 `~/oc-v1` 同源同版本） |
| `v2/Dockerfile` | OpenCode 2.0.18（compose 把宿主 `~/.opencode/bin/opencode` 只读挂载进容器——opencode.ai DNS 不通、npm 无 2.x、GitHub Release 资产 404，故以挂载锚定版本） |
| `dsh/Dockerfile` | DSH 0.1.7-rc.2（npm `@deepseek-ai/dsh`，与宿主 brew dsh 同版本） |
| `setup-secrets.sh` | 生成 `runtime/`（不入库）：workspace git 仓库、三面 provider 配置（zhipuai glm-5.3）、`.env`（密钥从宿主既有配置提取，绝不入库） |
| `wait-ready.sh` | 等三面健康检查全绿 + 提取 DSH launch token 到 `runtime/dsh-token` |
| `api_sweep.py` | 三面 API 巡检（唯三方依赖 `requests`） |
| `results/` | 巡检产物（json + md 矩阵） |

## 一键复跑

```bash
brew install colima docker docker-compose   # 一次性
brew install colima docker docker-compose && colima start   # 一次性（Linux；macOS 同）

cd scripts/regression
bash setup-secrets.sh                        # 生成 runtime/ 与 .env
docker compose up -d --build                 # 或无 compose 插件时用下方 docker run 等价命令
bash wait-ready.sh                           # 健康检查 + DSH token
pip install requests
python3 api_sweep.py --face all              # 三面全量巡检 → results/
```

无 compose 插件时的 `docker run` 等价（本仓库 2026-09-28 实测路径）：

```bash
source .env
N=ocbeacon-reg; docker network create $N 2>/dev/null || true
docker run -d --name ${N}-v1 --network $N -p 14199:14199 -e OPENCODE_SERVER_PASSWORD=$V1_PASSWORD \
  -e ZHIPU_API_KEY=$ZHIPU_API_KEY -v $PWD/runtime/v1-config:/root/.config/opencode \
  -v $PWD/runtime/v1-data:/root/.local/share/opencode -v $PWD/runtime/workspace:/workspace -w /workspace ocbeacon-reg/v1
docker run -d --name ${N}-v2 --network $N -p 14096:14096 -e OPENCODE_SERVER_PASSWORD=$V2_PASSWORD \
  -e ZHIPU_API_KEY=$ZHIPU_API_KEY -v $PWD/runtime/v2-config:/root/.config/opencode \
  -v $PWD/runtime/v2-data:/root/.local/share/opencode -v $PWD/runtime/workspace:/workspace \
  -v $HOME/.opencode/bin/opencode:/usr/local/bin/opencode:ro -w /workspace ocbeacon-reg/v2
docker run -d --name ${N}-dsh --network $N -p 14200:14200 -e DSH_HOME=/root/.dsh -e ZAI_API_KEY=$ZAI_API_KEY \
  -v $PWD/runtime/dsh-home:/root/.dsh -v $PWD/runtime/workspace:/workspace -w /workspace ocbeacon-reg/dsh
docker logs ${N}-dsh 2>&1 | grep -oE "token=[A-Za-z0-9_-]+" | tail -1 | cut -d= -f2 > runtime/dsh-token
```

## 巡检覆盖口径

- **V1**：以 `docs/opencode-api-reference-v1.md`（103 个 `### METHOD \`path\`` 标题，文档口径 129 端点含别名/变体）为权威清单逐项触达；SSE 全程收帧核对 `{id,type,properties}` 信封。
- **V2**：`GET /openapi.json` 活体路由（115 路径）+ app 消费侧 44 调用点（`data/api/v2/` grep 提取，脚本内嵌 `V2_CONSUMER`）双向对齐，漂移端点单独记行。
- **DSH**：栅栏矩阵（403/401/415）→ launch token 交换 → 15 个 RPC 方法面（`DshApiClient` 调用点提取）→ 信封铁律（URL≠body.method → bad-request）→ WS 三路径探测（remote.mux / events.mux / events.host）→ `/api/respond` 拒绝路径 → `session.export`。

## 已知环境事实（2026-09-28）

- opencode.ai 域名 DNS 不通；npm 无 `opencode-ai@2.x`；GitHub Release v2.0.18 linux 资产 404 → V2 版本锚定只能挂载宿主二进制。
- DSH 0.1.2+ 强制 cookie 鉴权（`GET /?token=` 交换）；容器/宿主重启后 token 轮换，须重跑 `wait-ready.sh`。
- LLM 实况链路用 zhipuai coding plan（glm-5.3 / glm-5.3-flash），密钥经 `setup-secrets.sh` 从宿主 `~/oc-v1-env` 提取注入。
