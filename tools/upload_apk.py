#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 APK 自动上传到蓝奏云「lanzou」文件夹（每版自动上传）。

依赖：requests、requests-toolbelt（同目录自带 lanzou_core.py）。

用法:
  python3 upload_apk.py --cookie <cookie> --apk <apk路径> [--folder lanzou] [--folder-id <id>]

cookie 支持两种传法：
  1. 直接传整行 cookie 字符串；
  2. 传 @文件路径（例如 --cookie @./cookie.txt），从文件读取（避免进 shell 历史）。

示例:
  python3 upload_apk.py --cookie @./cookie.txt --apk /workspace/lanzou-cloud-1.1.0-beta.1793111833.apk
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lanzou_core import Lanzou, LanzouError  # noqa: E402


def load_cookie(arg):
    if arg.startswith("@"):
        p = arg[1:]
        if not os.path.isfile(p):
            raise SystemExit("cookie 文件不存在: %s" % p)
        return open(p, encoding="utf-8").read().strip()
    return arg


def progress(name, total, now):
    pct = (now * 100.0 / total) if total else 0.0
    sys.stdout.write("\r上传中 %s  %s/%s  %.1f%%" % (name, now, total, pct))
    sys.stdout.flush()


def main():
    ap = argparse.ArgumentParser(description="上传 APK 到蓝奏云 lanzou 文件夹")
    ap.add_argument("--cookie", required=True, help="整行 cookie，或 @文件路径")
    ap.add_argument("--apk", required=True, help="APK 文件路径")
    ap.add_argument("--folder", default="lanzou", help="目标文件夹名（默认 lanzou）")
    ap.add_argument("--folder-id", default=None, help="直接指定 folder_id（跳过查找/创建）")
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        raise SystemExit("APK 不存在: %s" % args.apk)

    cookie = load_cookie(args.cookie)
    print("登录中...")
    lz = Lanzou(cookie)
    print("登录成功，uid=%s" % lz.uid)

    if args.folder_id:
        fid = int(args.folder_id)
        print("使用指定文件夹 folder_id=%d" % fid)
    else:
        fid = None
        for f in lz.list_dir(-1)["folders"]:
            if f["name"] == args.folder:
                fid = f["id"]
                break
        if fid is None:
            fid = lz.mkdir(-1, args.folder, "app 每版自动上传")
            print("已创建文件夹「%s」folder_id=%d" % (args.folder, fid))
        else:
            print("已存在文件夹「%s」folder_id=%d" % (args.folder, fid))

    print("开始上传: %s" % args.apk)
    t0 = time.time()
    info = lz.upload(args.apk, folder_id=fid, callback=progress)
    sys.stdout.write("\n")
    print("上传完成，耗时 %.1fs" % (time.time() - t0))
    print("文件 id=%s  name=%s  size=%s" % (info["id"], info["name"], info["size"]))

    share = lz.share(info["id"], is_file=True)
    print("\n=== 分享信息 ===")
    print("链接: %s" % share["url"])
    if share.get("pwd"):
        print("提取码: %s" % share["pwd"])
    else:
        print("提取码: 无")
    print("================\n")


if __name__ == "__main__":
    main()
