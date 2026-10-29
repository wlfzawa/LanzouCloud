# 协作约定

## 同步规则（双远端）

- 仓库有两个远端：`gitee`（主源，国内可达）、`github`（镜像）
- 代码更新后执行 **`tools/sync.sh`** 即可，不要手动连续 push 两个远端：
  ```bash
  tools/sync.sh            # 推 Gitee -> 推 GitHub -> 触发 Actions 立即同步
  tools/sync.sh --github   # GitHub 网络恢复后单独补推
  ```
- **GitHub 推送失败不算问题**：`.github/workflows/sync-gitee.yml` 每 20 分钟把 Gitee 领先的
  提交快进到 GitHub，`tools/sync.sh` 推完也会 `workflow_dispatch` 触发一次
- GitHub 有两条通道：优先 SSH remote `github-ssh`（走 `ssh.github.com:443`，实测 4 秒完成，
  而 HTTPS 443 会 135 秒超时），失败再退回 HTTPS。SSH 私钥在 `~/.ssh/id_ed25519_github`，
  公钥已加到 GitHub 账号；机器重建后 key 丢失会自动退回 HTTPS，或重新生成再用
  `POST /user/keys` 注册
- **Gitee 推送失败才算失败**：代码没落到任何远端，必须重试
- 工作流是双向的但**不覆盖分叉**：GitHub 领先时反向快进 Gitee（用 `GITEE_TOKEN`），
  两边分叉时报错退出，需人工合并后再同步
- 因此**不要直接在 GitHub 网页上改代码**（会被当成分叉）；要改就本地改、提交、同步

## 发布规则

- **只有明确要求时才发 Release**，没说要发就只同步代码
- 发 Release 用 `tools/publish.py`，Token 通过环境变量传入：
  ```bash
  GH_TOKEN=xxx GITEE_TOKEN=xxx python3 tools/publish.py \
      --release --apk /workspace/lanzou-cloud-<版本>.apk --notes /tmp/notes.md
  ```
- 版本号由 `app/build.gradle.kts` 自动生成（unix 时间戳），每次构建唯一

## 安全

- **绝不提交** `tools/cookie.txt`（蓝奏云登录凭据），已在 `.gitignore` 中排除
- Token 只走环境变量或命令行，不写进任何入库文件
- `GITEE_TOKEN` 另存于 GitHub Actions Secret，仅供同步工作流反向推送使用

## 构建

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleRelease
# zipalign + apksigner 后产物：/workspace/lanzou-cloud-<版本>.apk
```
