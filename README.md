# LanzouCloud

一个能在 **Android 2.3（API 9）** 上运行的蓝奏云第三方客户端。纯 Java 编写，不依赖 AndroidX，
用 wolfSSL 补上老系统缺失的 TLS 1.2。

> 仅用于学习与个人备份，请遵守蓝奏云服务条款，勿用于传播侵权内容。

## 功能

- **账号登录**：粘贴 Cookie 登录，或在高版本系统上用内置 WebView 登录并自动提取 Cookie
- **Cookie 导入**：支持 `json`（对象 / 数组 / 浏览器插件导出）与 `txt`（Cookie 字符串 / Netscape cookies.txt），导入后逐个在线校验
- **多账号管理**：登录过的账号自动保存，可一键切换、重命名、删除
- **文件管理**：浏览目录、进入子文件夹、上传文件、新建文件夹
- **文件分享**：生成文件 / 文件夹分享链接与提取码
- **分享链接下载**：支持 `https://xxx.lanzn.com/iXXXXXX` 单文件链接
- **文件夹分享浏览**：支持 `https://xxx.lanzout.com/bXXXXXX` 文件夹链接，输入提取码后列出文件，
  可单个下载，也可「全部下载」——自动翻完所有分页后逐个入队
- **剪贴板自动拾取**：回到 App 时自动识别剪贴板里的蓝奏云链接与提取码，一键打开；首次使用会先询问。
  本地只保存内容指纹（SHA-256 前 16 字节）用于 1 分钟内去重，**不保存剪贴板原文**
- **历史记录**：打开/下载过的链接、已完成/失败的下载任务都会持久保存，重启后仍可查看与打开
- **分卷上传大文件**：超过单卷大小（默认 90 MB）的文件自动切卷上传到同名子文件夹，
  并生成 `.lanzouvol.txt` 说明文件；完成后给出文件夹分享链接，对方打开点说明文件即可合并
- **分卷合并下载**：在网盘目录或文件夹分享里点说明文件，自动按清单下载全部分卷、
  顺序拼接并校验 SHA-256，进度按整文件大小显示
- **分片下载**：服务端支持 Range 时多线程分段下载（默认 4 线程），断点续传，
  中断后保留 `.part` 与进度文件，重试从断点继续
- **内建下载器**：后台队列、进度显示、失败重试、重名自动改名
- **下载位置**：设置里可直接用系统文件选择器选文件夹（Android 5.0+，无需存储权限），也支持输入路径
- **外观**：亮色 / 深色 / 跟随系统三档，切换即时生效
- **Cookie 导出**：复制到剪贴板或导出 txt 分享

## 下载

| 渠道 | 地址 |
|---|---|
| GitHub Releases | https://github.com/wlfzawa/LanzouCloud/releases |
| Gitee Releases | https://gitee.com/wlfzawa/LanzouCloud/releases |
| 蓝奏云 | https://wwbkc.lanzn.com/i1U2J4e1cqeh 提取码 `83yv` |

同一版本用同一证书签名，可直接覆盖安装。

当前版本：`1.4.0-beta.1793441279`（2.9 MB）

## 兼容性

| 项目 | 值 |
|---|---|
| minSdk | **9（Android 2.3 Gingerbread）** |
| targetSdk | 34 |
| ABI | `armeabi-v7a`、`arm64-v8a` |
| 签名 | v1（JAR）+ v2 |
| 版本号 | `1.4.0-beta.<unix 时间戳>`，每构建一次自动递增 |

深色模式：Android 4.2（API 17）及以上可手动切换；更低版本没有覆盖 `uiMode` 的公开 API，
只能使用亮色（系统本身也没有全局深色）。

TLS 按系统版本自动分层：

| 系统版本 | 使用的 TLS |
|---|---|
| API 9 ~ 15 | **wolfSSL**（系统不支持 TLS 1.2） |
| API 16 ~ 20 | 系统 TLS，强制 `TLSv1.2` |
| API 21+ | 系统默认 |

wolfSSL 加载失败时自动降级到系统 TLS，不会崩溃。

## 为什么需要 wolfSSL

Android 2.3 的系统 TLS 栈只到 SSL 3.0 / TLS 1.0，且不支持 SNI；蓝奏云的接口与 CDN
（`pc.woozooo.com`、`slsstm2.dmpdmp.com`、`h1053.lanosso.com`）只接受 TLS 1.2，握手会直接失败。

这里按老豆荚、BiliClassic 等项目的思路引入 **wolfSSL C 库**，自写极简 JNI 绑定，不复用
wolfssljni 的 Java 层（它继承 `ExtendedSSLSession` 等 API 24+ 类型，在 API 9 上类加载即失败）。

三个关键处理：

1. **NDK r16b**：r17 起最低只支持 `android-16`，r21e 会引入 `__stack_chk_fail` 等 API 14+ 符号，
   而 Android 2.3 的 libc 没有这些符号，`dlopen` 会失败。r16b 是最后一个支持 `android-9` 的 NDK。
2. **关闭 BIND_NOW**：链接时追加 `-Wl,-z,lazy`，避免加载期解析全部符号。
3. **补齐 C++ EH 符号**：clang 的 `-funwind-tables` 引入 `__cxa_call_unexpected` 等符号，
   Android 2.3 的 libc 不导出 → 用 `cxa_stub.c` 提供空实现（wolfSSL 是纯 C，不会走到异常路径）。

重新构建 native 库：`tools/build_wolfssl.sh [ABI] [android-API]`

## 其他 API 9 兼容改造

| 系统组件 | 问题 | 处理 |
|---|---|---|
| `Theme.Holo.*` | API 11+ | 改用 `Theme.Light.NoTitleBar` / `Theme.NoTitleBar` |
| `values-night` + `createConfigurationContext` | 手动切深色需 API 17+ | 低版本仅亮色，见上文 |
| `android.widget.Switch` | API 14+ | 改用 `CheckBox` |
| `VectorDrawable` | API 21+ | 改用各密度 PNG |
| `androidx.core` FileProvider | 最低 API 14 | 自研 `MiniFileProvider` |
| OkHttp 3.12 | `callTimeout` 引用 `java.time.Duration`（API 26） | 降到 3.11.0 |

## 构建

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release-unsigned.apk`，需自行 zipalign + apksigner 签名。

## 实现要点

- **acw_sc__v2 挑战**：阿里云 ESA 的 JS 挑战，用纯 Java 移植 `_unsbox` + `_hexXor` 算法；
  关键在于 `acw_tc` / `cdn_sec_tc` 只按域发送，不能作为全局 Cookie 跨域带到 CDN
- **「下载得到 HTML」防护**：按正文内容识别页面（CDN 会把错误页标成 `application/octet-stream`），
  挑战页/验证页/跳转页全部在内存里处理，绝不写入文件；失败时保存诊断页供排查
- **文件名还原**：蓝奏云分享页 `<title>` 已改成「文件」，改为优先解析
  `Content-Disposition`，已入队任务会自动改名
- **文件夹分享**：`filemoreajax.php` 接口，`t`/`k` 校验变量名每次请求随机生成，
  按值的形态（10 位时间戳 / 32 位十六进制）识别

## 已知限制

- 单次上传无分块 / 断点续传
- 子文件夹列表接口（`task=47`）已被服务端下线，网盘内看不到嵌套子文件夹
- 文件夹分享每页 50 条，翻页接口有频率限制，过快会返回「请刷新，重试」
- **未在真实 Android 2.3 设备上验证**：已完成 minSdk、v1 签名、native 符号表、
  dex 高版本引用扫描等静态验证；真机加载失败会自动降级系统 TLS

## 更新日志

| 版本 | 内容 |
|---|---|
| `1.4.0-beta.<ts>` | 大文件分卷上传（自动建子文件夹 + 说明文件 + 文件夹分享）；点说明文件自动合并下载并校验；分片下载与断点续传；长按上传按钮强制分卷 |
| `1.3.0-beta.<ts>` | 文件夹分享支持一键全部下载（自动翻页）；根目录按返回键二次确认；剪贴板自动拾取；亮色/深色/跟随系统；链接与下载历史持久化；json/txt 导入 Cookie；系统文件夹选择器指定下载目录 |
| `1.2.0-beta.<ts>` | 新增文件夹分享浏览；图标自适应；下载文件名修复；下载 HTML 彻底防护；运行时存储权限；账号管理；Cookie 导出 |
| `1.1.0-beta` | WebView 登录、设置界面、内建下载器 |
| `1.0.0` | 首个版本：登录、上传、下载 |

## 同步与发布约定

- 每次代码更新都会同步到 GitHub 与 Gitee，直接执行 `tools/sync.sh` 即可
- **Gitee 为主源**：脚本先推 Gitee（国内可达），再尽力推 GitHub
- GitHub 走两条通道：优先 SSH（`ssh.github.com:443`，国内一般可用），失败再走 HTTPS；
  两条都不通也没关系 —— Actions 每 20 分钟把 Gitee 领先的提交快进到 GitHub，
  推送结束后脚本还会主动触发一次，通常几十秒内同步完成
- 若 GitHub 反而领先（例如网页上直接改过文件），工作流会用 `GITEE_TOKEN` 反向快进到 Gitee；
  两边分叉时只报错、不覆盖，需要人工合并
- 定时任务由 GitHub Actions 提供：仓库连续 60 天无提交会被平台暂停，推一次代码即恢复
- **只有明确要求时才发 Release**，否则只推代码（`tools/publish.py --release`）
