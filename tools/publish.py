#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发布助手：同步代码到 GitHub / Gitee，按需创建 Release 并上传 APK。

Token 从环境变量读取，不入库：
    export GH_TOKEN=...
    export GITEE_TOKEN=...

用法：
    # 只推代码（默认，不发 Release）
    python3 tools/publish.py

    # 发 Release：自动上传蓝奏云 + 两个平台的 Release 附件
    python3 tools/publish.py --release --apk ../lanzou-cloud-1.2.0-beta.1793203327.apk \
        --notes /tmp/release_body.md

约定：**没有明确要求时不发 Release**，只同步代码。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def sh(cmd, cwd=ROOT):
    print("+", " ".join(cmd))
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if p.stdout.strip():
        print(p.stdout.strip())
    if p.returncode != 0:
        print(p.stderr.strip(), file=sys.stderr)
        raise SystemExit("命令失败: %s" % cmd)
    return p.stdout


def push_code():
    for remote in ("github", "gitee"):
        try:
            sh(["git", "push", remote, "main"])
        except SystemExit:
            print("!! %s 推送失败，稍后可单独重试：git push %s main" % (remote, remote))


def github_release(token, version, apk, notes):
    api = "https://api.github.com/repos/wlfzawa/LanzouCloud/releases"
    payload = json.dumps({
        "tag_name": "v" + version, "target_commitish": "main",
        "name": "v" + version, "body": notes,
        "draft": False, "prerelease": True,
    }, ensure_ascii=False).encode()
    req = urllib.request.Request(api, data=payload, method="POST", headers={
        "Authorization": "token " + token,
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            d = json.load(r)
    except urllib.error.HTTPError as e:
        print("GitHub Release 创建失败:", e.code, e.read().decode()[:300])
        return
    rid = d["id"]
    print("GitHub Release:", d["html_url"])

    name = os.path.basename(apk)
    url = "https://uploads.github.com/repos/wlfzawa/LanzouCloud/releases/%d/assets?name=%s" % (rid, name)
    with open(apk, "rb") as f:
        data = f.read()
    req = urllib.request.Request(url, data=data, method="POST", headers={
        "Authorization": "token " + token,
        "Content-Type": "application/vnd.android.package-archive"})
    with urllib.request.urlopen(req, timeout=300) as r:
        a = json.load(r)
    print("  asset:", a.get("browser_download_url"))


def gitee_release(token, version, apk, notes):
    base = "https://gitee.com/api/v5/repos/wlfzawa/LanzouCloud/releases"
    data = urllib.parse.urlencode({
        "access_token": token, "tag_name": "v" + version,
        "target_commitish": "main", "name": "v" + version,
        "body": notes, "prerelease": "true"}).encode()
    req = urllib.request.Request(base, data=data, method="POST",
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            d = json.load(r)
    except urllib.error.HTTPError as e:
        print("Gitee Release 创建失败:", e.code, e.read().decode()[:300])
        return
    rid = d["id"]
    print("Gitee Release: id=%s tag=v%s" % (rid, version))

    # 附件走 multipart，优先用 requests；没有则退回纯标准库
    try:
        import requests  # noqa
    except ImportError:
        print("  未安装 requests，跳过 Gitee 附件上传")
        return
    with open(apk, "rb") as f:
        r = requests.post("%s/%s/attach_files" % (base, rid),
                          params={"access_token": token},
                          files={"file": (os.path.basename(apk), f,
                                          "application/vnd.android.package-archive")},
                          timeout=300)
    if r.status_code < 300:
        print("  asset:", r.json().get("browser_download_url"))
    else:
        print("  附件上传失败:", r.status_code, r.text[:200])


def upload_lanzou(apk):
    """调用现有上传脚本，返回分享链接与提取码"""
    script = os.path.join(ROOT, "tools", "upload_apk.py")
    cookie = os.path.join(ROOT, "tools", "cookie.txt")
    if not os.path.exists(cookie):
        print("!! 缺少 tools/cookie.txt，跳过蓝奏云上传")
        return None
    p = subprocess.run([sys.executable, script, "--cookie", "@" + cookie,
                        "--apk", apk, "--folder-id", "14149764"],
                       cwd=ROOT, capture_output=True, text=True)
    print(p.stdout.strip())
    m_url = re.search(r"链接:\s*(\S+)", p.stdout or "")
    m_pwd = re.search(r"提取码:\s*(\S+)", p.stdout or "")
    if m_url:
        return m_url.group(1), (m_pwd.group(1) if m_pwd else "")
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", action="store_true", help="创建 Release 并上传 APK")
    ap.add_argument("--apk", help="APK 路径（--release 时必需）")
    ap.add_argument("--version", help="版本名，默认从 APK 文件名推断")
    ap.add_argument("--notes", help="Release 说明文件（Markdown）")
    ap.add_argument("--no-push", action="store_true", help="跳过 git push")
    a = ap.parse_args()

    if not a.no_push:
        push_code()

    if not a.release:
        print("\n只同步了代码。需要发 Release 时加 --release --apk <文件>")
        return

    if not a.apk or not os.path.exists(a.apk):
        raise SystemExit("--apk 不存在")

    version = a.version
    if not version:
        m = re.search(r"lanzou-cloud-(.+)\.apk$", os.path.basename(a.apk))
        version = m.group(1) if m else None
    if not version:
        raise SystemExit("无法推断版本号，请用 --version 指定")

    notes = ""
    if a.notes and os.path.exists(a.notes):
        notes = open(a.notes, encoding="utf-8").read()

    share = upload_lanzou(a.apk)
    if share:
        notes += "\n\n## 下载\n\n| 渠道 | 地址 |\n|---|---|\n| 蓝奏云 | %s 提取码 `%s` |\n" % share

    gh = os.environ.get("GH_TOKEN")
    gt = os.environ.get("GITEE_TOKEN")
    if gh:
        github_release(gh, version, a.apk, notes)
    else:
        print("!! 未设置 GH_TOKEN，跳过 GitHub Release")
    if gt:
        gitee_release(gt, version, a.apk, notes)
    else:
        print("!! 未设置 GITEE_TOKEN，跳过 Gitee Release")


if __name__ == "__main__":
    main()
