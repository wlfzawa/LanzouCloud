# 协作约定

## 发布规则

- **每次代码更新都要同步到两个远端**：`git push github main`、`git push gitee main`
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

## 构建

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleRelease
# zipalign + apksigner 后产物：/workspace/lanzou-cloud-<版本>.apk
```
