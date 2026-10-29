#!/usr/bin/env bash
# 双远端同步：Gitee 为主（国内可达），GitHub 为镜像。
#
# 用法：
#   tools/sync.sh              # 推送当前分支：先 Gitee，再 GitHub，最后触发 Actions 兜底
#   tools/sync.sh --github     # 只推 GitHub（GitHub 网络恢复时补推）
#   tools/sync.sh --gitee      # 只推 Gitee
#
# Token 来源：优先环境变量 GH_TOKEN / GITEE_TOKEN，其次从 remote URL 里解析。
# 说明：GitHub 推送失败不算失败，返回码仍为 0，由 .github/workflows/sync-gitee.yml
#       每 20 分钟从 Gitee 快进过去；Gitee 推送失败才会返回非 0。
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

BRANCH=$(git branch --show-current)
[ -n "$BRANCH" ] || { echo "当前处于游离 HEAD，请先切到分支"; exit 1; }

MODE="both"
case "${1:-}" in
  --github) MODE="github" ;;
  --gitee)  MODE="gitee" ;;
  "")       ;;
  *) echo "未知参数：$1"; exit 2 ;;
esac

# 从 remote URL 里取出 https://user:token@host/... 的 token
token_of() {
  git remote get-url "$1" 2>/dev/null | sed -n 's#.*://[^/:]*:\([^@]*\)@.*#\1#p'
}

REMOTE_GH_TOKEN="${GH_TOKEN:-$(token_of github)}"
REMOTE_GITEE_TOKEN="${GITEE_TOKEN:-$(token_of gitee)}"

if [ -n "$(git status --porcelain)" ]; then
  echo "提示：工作区有未提交改动，本次只推送已提交的内容"
fi

retry() {
  local n=$1; shift
  local i
  for i in $(seq 1 "$n"); do
    if "$@"; then return 0; fi
    [ "$i" -lt "$n" ] && echo "  第 $i 次失败，5 秒后重试" && sleep 5
  done
  return 1
}

GIT_OPTS=(-c http.postBuffer=524288000)
export GIT_HTTP_LOW_SPEED_LIMIT=1
export GIT_HTTP_LOW_SPEED_TIME=900

PUSH_GITEE=0
PUSH_GITHUB=0

if [ "$MODE" = "both" ] || [ "$MODE" = "gitee" ]; then
  echo "== 推送到 Gitee（主源）"
  if retry 3 git "${GIT_OPTS[@]}" push gitee "$BRANCH"; then
    PUSH_GITEE=1
    echo "   Gitee 完成"
  else
    echo "!! Gitee 推送失败，代码没有落到任何远端，请检查网络后重试" >&2
    exit 1
  fi
fi

if [ "$MODE" = "both" ] || [ "$MODE" = "github" ]; then
  echo "== 推送到 GitHub（镜像）"

  # github.com:443 经常连不上，但 ssh.github.com:443 通常可用，所以优先走 SSH
  if ! git remote | grep -qx 'github-ssh'; then
    SSH_URL=$(git remote get-url github 2>/dev/null \
      | sed -E 's#^https?://([^@/]*@)?github\.com/#git@github.com:#')
    case "$SSH_URL" in
      git@github.com:*) git remote add github-ssh "$SSH_URL" 2>/dev/null ;;
    esac
  fi

  GH_OK=0
  if git remote | grep -qx 'github-ssh'; then
    echo "   · SSH 通道"
    if retry 2 timeout 300 git push github-ssh "$BRANCH"; then GH_OK=1; fi
  fi
  if [ "$GH_OK" = "0" ]; then
    echo "   · HTTPS 通道"
    if retry 2 timeout 150 git "${GIT_OPTS[@]}" push github "$BRANCH"; then GH_OK=1; fi
  fi

  if [ "$GH_OK" = "1" ]; then
    PUSH_GITHUB=1
    echo "   GitHub 完成"
  else
    echo "!! GitHub 推送失败（常见：网络慢/限速）"
    echo "   Gitee 已是最新，Actions 会在 20 分钟内自动补齐 GitHub，也可稍后执行：tools/sync.sh --github"
  fi
fi

# 推送成功后触发一次镜像工作流，省去等待定时任务
if [ "$PUSH_GITEE" = "1" ] && [ -n "$REMOTE_GH_TOKEN" ]; then
  REPO_PATH=$(git remote get-url github 2>/dev/null | sed -n 's#.*github\.com[:/]\(.*\)\.git#\1#p')
  [ -n "$REPO_PATH" ] || REPO_PATH="wlfzawa/LanzouCloud"
  echo "== 触发 GitHub Actions 立即同步"
  CODE=$(curl -sS -o /tmp/sync-dispatch.json -w '%{http_code}' -X POST \
    -H "Authorization: token $REMOTE_GH_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${REPO_PATH}/actions/workflows/sync-gitee.yml/dispatches" \
    -d "{\"ref\":\"${BRANCH}\"}" 2>/dev/null)
  if [ "$CODE" = "204" ]; then
    echo "   已触发"
  else
    echo "   触发失败（HTTP ${CODE:-无响应}），不影响代码，定时任务会兜底"
  fi
fi

exit 0
