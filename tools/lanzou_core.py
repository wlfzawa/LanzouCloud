#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""蓝奏云网盘客户端 —— 直接对接 2026 年现行 Web 接口，只依赖 requests。

为什么不直接用 lanzou-api 2.6.10：
    该库（2024-03 最后更新）与线上接口已严重脱节，实测以下环节全部失效：
      1. 登录判定 —— 它读 account.php 页面里的「网盘用户登录」字样，但该页现在 302 到
         account.php?action=login，导致**有效 cookie 也被判过期**；
      2. 文件夹列表 task=47 —— 服务端已下线，恒返回 0 字节；
      3. 文件列表 task=5 —— 变成了「每页 18 条、文件夹元数据混排进文件对象」的新结构；
      4. 上传 fileup.php —— 已 404，实际端点是 html5up.php，且目标文件夹靠 folder_id_c cookie 传；
      5. 下载 —— 改走 apifile.woozooo.com/ajaxfile.php，分享域名也换成了 lanzn.com 系列，
         原来的 is_file_url() 正则匹配不上；
      6. 分享页与直链下载都套了 acw_sc__v2 JS 挑战。
    所以这里按实测结果重写，只保留原库的 acw 挑战算法。

公开接口见类 Lanzou。所有失败统一抛 LanzouError(code, msg)。
"""

import os
import re
import time
from urllib.parse import urlsplit

import requests
from requests_toolbelt import MultipartEncoder, MultipartEncoderMonitor
from urllib3 import disable_warnings
from urllib3.exceptions import InsecureRequestWarning

disable_warnings(InsecureRequestWarning)

# ---------------------------------------------------------------- 常量

HOST = "https://pc.woozooo.com"
URL_DOUPLOAD = HOST + "/doupload.php"
URL_MDISK = HOST + "/mydisk.php"
URL_UPLOAD = HOST + "/html5up.php"
URL_AJAXFILE = "https://apifile.woozooo.com/ajaxfile.php"

SUCCESS = 0
FAILED = -1
ID_ERROR = 1
PASSWORD_ERROR = 2
LACK_PASSWORD = 3
MKDIR_ERROR = 5
URL_INVALID = 6
PATH_ERROR = 8
NETWORK_ERROR = 9
OFFICIAL_LIMITED = 11

CODE_MSG = {
    SUCCESS: "成功",
    FAILED: "操作失败",
    ID_ERROR: "ID 无效或类型不匹配",
    PASSWORD_ERROR: "提取码错误",
    LACK_PASSWORD: "缺少提取码",
    MKDIR_ERROR: "创建文件夹失败",
    URL_INVALID: "分享链接无效",
    PATH_ERROR: "本地路径错误",
    NETWORK_ERROR: "网络错误",
    OFFICIAL_LIMITED: "超出官方限制",
}

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

# 官方允许上传的后缀
VALID_SUFFIX = {
    "ppt", "xapk", "ke", "azw", "cpk", "gho", "dwg", "db", "docx", "deb", "e", "ttf", "xls",
    "bat", "crx", "rpm", "txf", "pdf", "apk", "ipa", "txt", "mobi", "osk", "dmg", "rp", "osz",
    "jar", "ttc", "z", "w3x", "xlsx", "cetrainer", "ct", "rar", "mp3", "pptx", "mobileconfig",
    "epub", "imazingapp", "doc", "iso", "img", "appimage", "7z", "rplib", "lolgezi", "exe",
    "azw3", "zip", "conf", "tar", "dll", "flac", "xpa", "lua", "cad", "hwt", "accdb", "ce",
    "xmind", "enc", "bds", "bdi", "ssf", "it", "gz",
}

# 分享域名白名单前缀（含 2026 年新增的 lanzn / lanrar 等）
_SHARE_RE = re.compile(r"https?://[A-Za-z0-9.-]*\.?lan[a-z0-9-]*\.com/[A-Za-z0-9]+")


class LanzouError(Exception):
    def __init__(self, code, msg=None):
        self.code = code
        self.msg = msg or CODE_MSG.get(code, "错误码 %s" % code)
        super().__init__(self.msg)


# ---------------------------------------------------------------- acw_sc__v2 挑战

def _unsbox(s):
    box = [15, 35, 29, 24, 33, 16, 1, 38, 10, 9, 19, 31, 40, 27, 22, 23, 25, 13, 6, 11,
           39, 18, 20, 8, 14, 21, 32, 26, 2, 30, 7, 4, 17, 5, 3, 28, 34, 37, 12, 36]
    out = [""] * len(box)
    for i, ch in enumerate(s):
        for j, pos in enumerate(box):
            if pos == i + 1:
                out[j] = ch
    return "".join(out)


def calc_acw_sc__v2(html):
    """从挑战页 HTML 算出 acw_sc__v2 cookie 值"""
    m = re.search(r"arg1='([0-9A-Z]+)'", html)
    if not m:
        return ""
    arg1 = m.group(1)
    key = "3000176000856006061501533003690027800375"
    src = _unsbox(arg1)
    out = []
    for i in range(0, min(len(src), len(key)), 2):
        out.append("%02x" % (int(src[i:i + 2], 16) ^ int(key[i:i + 2], 16)))
    return "".join(out)


def is_challenge(text):
    """判断是否为 acw_sc__v2 挑战页

    挑战页正文形如 <html><script>var arg1='...';(function(a,c){...
    arg1 一定在最开头；而 acw_sc__v2 字样出现在页面后段的赋值语句里，
    所以只认 arg1，别再要求 acw_sc__v2（曾因此漏判、反复拿到挑战页）。
    """
    return "arg1='" in text[:400]


# ---------------------------------------------------------------- 工具

def is_file_url(url):
    """是否为文件分享链接（不联网，只按形态判断）"""
    if not _SHARE_RE.fullmatch(url.strip()):
        return False
    return not is_folder_url(url)


def is_folder_url(url):
    """是否为文件夹分享链接

    文件夹分享页通常是 /bXXXXXX 或 /s/bXXXXXX。
    """
    if not _SHARE_RE.fullmatch(url.strip()):
        return False
    path = urlsplit(url.strip()).path.strip("/")
    return bool(re.fullmatch(r"(s/)?b[A-Za-z0-9]{6,}", path))


def safe_name(name):
    name = name.replace("\xa0", " ").replace("\u3000", " ")
    return re.sub(r'[$%^!*<>)(+=`\'"/:;,?]', "", name).strip()


def auto_rename(path):
    if not os.path.exists(path):
        return path
    base, ext = os.path.splitext(path)
    i = 1
    while os.path.exists("%s(%d)%s" % (base, i, ext)):
        i += 1
    return "%s(%d)%s" % (base, i, ext)


def _size_str(n):
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return ("%d%s" % (n, unit)) if unit == "B" else ("%.1f%s" % (n, unit))
        n /= 1024.0


# ---------------------------------------------------------------- 客户端

class Lanzou(object):
    """蓝奏云客户端

    用法::

        lz = Lanzou(cookie_dict)      # 构造时即校验登录态，失效抛 LanzouError
        lz.list_dir(-1)               # -> ({'folders': [...], 'files': [...]})
        lz.upload("a.txt", folder_id=-1)
        lz.download_by_id(123456, "./dl")
    """

    def __init__(self, cookie=None, timeout=30, verify=False):
        self.timeout = timeout
        self.session = requests.Session()
        self.uid = None
        self._headers = {
            "User-Agent": UA,
            "Referer": HOST + "/mydisk.php",
            "Accept-Language": "zh-CN,zh;q=0.9",
        }
        if cookie:
            self.login(cookie)

    # ---------------- 底层

    def _post(self, data, url=URL_DOUPLOAD, timeout=None, headers=None):
        try:
            r = self.session.post(url, data, headers=headers or self._headers,
                                  timeout=timeout or self.timeout, verify=False)
        except requests.RequestException as exc:
            raise LanzouError(NETWORK_ERROR, "请求失败: %s" % exc)
        if r.status_code != 200:
            raise LanzouError(FAILED, "HTTP %s: %s" % (r.status_code, url))
        return r

    def _api(self, data, **kw):
        """调 doupload 接口，返回 JSON dict"""
        d = dict(data)
        url = URL_DOUPLOAD + ("?uid=%s" % self.uid if self.uid else "")
        r = self._post(d, url=url, **kw)
        if not r.content:
            # 服务端对「无数据」返回空 body，不是错误
            return {"zt": 1, "info": 0, "text": []}
        try:
            return r.json()
        except ValueError:
            raise LanzouError(FAILED, "服务端返回非 JSON: %s" % r.text[:120])

    def _get_html(self, url, referer=None, timeout=None):
        """GET 页面，自动过 acw_sc__v2 挑战"""
        headers = dict(self._headers)
        if referer:
            headers["Referer"] = referer
        try:
            r = self.session.get(url, headers=headers, timeout=timeout or self.timeout,
                                 verify=False, allow_redirects=True)
            if r.status_code == 200 and is_challenge(r.text):
                host = urlsplit(r.url).hostname
                self.session.cookies.set("acw_sc__v2", calc_acw_sc__v2(r.text), domain=host)
                r = self.session.get(url, headers=headers, timeout=timeout or self.timeout,
                                     verify=False, allow_redirects=True)
        except requests.RequestException as exc:
            raise LanzouError(NETWORK_ERROR, "请求失败: %s" % exc)
        return r

    # ---------------- 登录

    def login(self, cookie):
        """注入 cookie 并校验登录态；失效抛 LanzouError(ID_ERROR)"""
        if isinstance(cookie, str):
            cookie = parse_cookie(cookie)
        if not cookie.get("ylogin"):
            raise LanzouError(ID_ERROR, "cookie 缺少 ylogin 字段")
        self.uid = cookie["ylogin"]
        self.session.cookies.update(cookie)
        self.check_login()
        return self

    def check_login(self):
        """用 doupload 探针确认登录态。

        注意：不要用 account.php 判断 —— 它现在会 302 到 account.php?action=login，
        有效 cookie 也会被误判成未登录。
        """
        d = self._api({"task": 5, "folder_id": -1, "pg": 1})
        if d.get("zt") != 1:
            raise LanzouError(ID_ERROR, "cookie 已失效，请重新从浏览器复制")
        return True

    # ---------------- 列表

    def raw_items(self, folder_id=-1, max_pages=200):
        """取某文件夹下的原始条目（文件与子文件夹混排）"""
        items, page = [], 1
        while page <= max_pages:
            d = self._api({"task": 5, "folder_id": folder_id, "pg": page})
            if d.get("zt") != 1:
                raise LanzouError(FAILED, "获取列表失败: %s" % d.get("info"))
            batch = d.get("text") or []
            if not batch:
                break
            items.extend(batch)
            page += 1
        return items

    @staticmethod
    def _norm_file(it):
        name = it.get("name_all") or it.get("name") or ""
        return {
            "kind": "file",
            "id": int(it["id"]),
            "name": name.replace("&amp;", "&"),
            "size": (it.get("size") or "").replace(",", ""),
            "time": it.get("time") or "",
            "type": (it.get("name_all") or "").split(".")[-1].lower(),
            "downs": int(it.get("downs") or 0),
            "has_pwd": str(it.get("onof")) == "1",
            "desc": it.get("desc") or "",
        }

    @staticmethod
    def _norm_folder(it, folder_id=None):
        fid = it.get("folder_id") or folder_id
        return {
            "kind": "folder",
            "id": int(fid),
            "name": it.get("folder_name") or it.get("name") or "",
            "size": it.get("folder_size") or "",
            "time": it.get("in_time") or "",
            "type": "folder",
            "downs": 0,
            "has_pwd": str(it.get("offon")) == "1",
            "desc": it.get("folder_description") or "",
        }

    def root_folders(self):
        """根目录文件夹列表（task=19）。

        注意：task=47（真正的「列子文件夹」接口）已被服务端下线，
        task=19 只返回根目录的文件夹，且会忽略 folder_id 参数。
        """
        d = self._api({"task": 19, "folder_id": -1})
        out = []
        for it in d.get("info") or []:
            if isinstance(it, dict) and it.get("folder_id"):
                out.append({
                    "kind": "folder",
                    "id": int(it["folder_id"]),
                    "name": it.get("folder_name") or "",
                    "size": "", "time": "", "type": "folder",
                    "downs": 0, "has_pwd": False, "desc": "",
                })
        return out

    def list_dir(self, folder_id=-1):
        """列目录，返回 {'folders': [...], 'files': [...]}

        task=5 的返回里，非根目录的「文件条目」会捎带父文件夹的元数据，
        所以判定规则是：有 folder_id 且**没有** id 的才是子文件夹条目。
        """
        folders, files = [], []
        for it in self.raw_items(folder_id):
            if "id" in it:
                files.append(self._norm_file(it))
            elif it.get("folder_id"):
                folders.append(self._norm_folder(it))
        if folder_id in (-1, "-1", None):
            seen = {f["id"] for f in folders}
            folders.extend(f for f in self.root_folders() if f["id"] not in seen)
        return {"folders": folders, "files": files}

    def resolve_path(self, path):
        """把 'a/b' 解析成 folder_id。路径不存在抛 LanzouError(ID_ERROR)"""
        if path in (None, "", "/", -1, "-1"):
            return -1
        try:
            return int(path)
        except (TypeError, ValueError):
            pass
        cur = -1
        for seg in [p for p in str(path).replace("\\", "/").split("/") if p]:
            folders = self.list_dir(cur)["folders"]
            hit = next((f for f in folders if f["name"] == seg), None)
            if hit is None:  # 宽松匹配：忽略空格/下划线差异
                norm = seg.replace(" ", "_")
                hit = next((f for f in folders
                            if f["name"].replace(" ", "_") == norm), None)
            if hit is None:
                raise LanzouError(ID_ERROR, "路径不存在: %s (在 #%s 下找不到 '%s')"
                                  % (path, cur, seg))
            cur = hit["id"]
        return cur

    # ---------------- 文件夹操作

    def mkdir(self, parent_id, name, desc=""):
        """创建文件夹，返回新文件夹 id"""
        name = safe_name(name).replace(" ", "_")
        if not name:
            raise LanzouError(MKDIR_ERROR, "文件夹名过滤后为空")
        d = self._api({"task": 2, "parent_id": parent_id or -1,
                       "folder_name": name, "folder_description": desc or ""})
        if d.get("zt") != 1:
            raise LanzouError(MKDIR_ERROR, "创建文件夹失败: %s" % d.get("info"))
        return int(d["text"])

    def delete(self, fid, is_file=True):
        """移入回收站"""
        d = self._api({"task": 6, "file_id": fid} if is_file
                      else {"task": 3, "folder_id": fid})
        if d.get("zt") != 1:
            raise LanzouError(FAILED, "删除失败: %s" % d.get("info"))

    def purge(self, fid, is_file=True):
        """从回收站彻底删除"""
        if is_file:
            para = {"item": "recycle", "action": "file_delete_complete", "file_id": fid}
            data = {"action": "file_delete_complete", "task": "file_delete_complete",
                    "file_id": fid}
        else:
            para = {"item": "recycle", "action": "folder_delete_complete", "folder_id": fid}
            data = {"action": "folder_delete_complete", "task": "folder_delete_complete",
                    "folder_id": fid}
        page = self.session.get(URL_MDISK, params=para, headers=self._headers,
                                timeout=self.timeout, verify=False)
        page.encoding = "utf-8"
        m = re.search(r'name="formhash" value="(\w+?)"', page.text)
        if not m:
            raise LanzouError(FAILED, "回收站页面里找不到 formhash，无法彻底删除")
        data["formhash"] = m.group(1)
        r = self.session.post(URL_MDISK + "?item=recycle", data, headers=self._headers,
                              timeout=self.timeout, verify=False)
        r.encoding = "utf-8"
        if "删除成功" not in r.text:
            raise LanzouError(FAILED, "彻底删除失败")

    # ---------------- 分享 / 信息

    def share(self, fid, is_file=True):
        """获取分享链接与提取码 -> {'name','url','pwd','desc'}"""
        d = self._api({"task": 22, "file_id": fid} if is_file
                      else {"task": 18, "folder_id": fid})
        info = d.get("info")
        if not isinstance(info, dict):
            raise LanzouError(ID_ERROR, "取不到分享信息，id 无效或类型不对")
        if is_file:
            url = (info.get("is_newd") or "") + "/" + (info.get("f_id") or "")
            name = info.get("name") or ""
            desc = info.get("pwd") or ""
            try:
                d12 = self._api({"task": 12, "file_id": fid})
                name = d12.get("text") or name
                desc = d12.get("info") or ""
            except LanzouError:
                pass
        else:
            url = info.get("new_url") or ""
            name = info.get("name") or ""
            desc = info.get("des") or ""
        if not url:
            raise LanzouError(FAILED, "服务端没有返回分享链接")
        return {
            "name": name,
            "url": url,
            "pwd": (info.get("pwd") or "") if str(info.get("onof")) == "1" else "",
            "desc": desc,
        }

    # ---------------- 上传

    def upload(self, file_path, folder_id=-1, callback=None):
        """上传单个文件。

        目标文件夹靠 multipart 里的 folder_id_bb_n 字段传递 —— 实测
        folder_id 字段和 folder_id_c cookie 都**不生效**（文件会落到根目录），
        只有 folder_id_bb_n 才被 html5up.php 认。

        返回新文件信息 dict（含 id / name / share_url）。
        """
        if not os.path.isfile(file_path):
            raise LanzouError(PATH_ERROR, "本地文件不存在: %s" % file_path)
        filename = os.path.basename(file_path)
        suffix = filename.split(".")[-1].lower() if "." in filename else ""
        if suffix not in VALID_SUFFIX:
            raise LanzouError(OFFICIAL_LIMITED,
                              "后缀 %r 不在官方允许上传的白名单里，请先压缩成 zip" % suffix)

        with open(file_path, "rb") as fh:
            fields = {
                "task": "1", "vie": "2", "ve": "2",
                "folder_id_bb_n": str(folder_id or -1),
                "name": filename,
                "upload_file": (filename, fh, "application/octet-stream"),
            }
            encoder = MultipartEncoder(fields)
            if callback is not None:
                last = [0.0]

                def _cb(monitor):
                    now = time.time()
                    if now - last[0] >= 0.5 or monitor.bytes_read >= monitor.len:
                        last[0] = now
                        # monitor.len 是整个 multipart 请求体的长度（含表单头），
                        # 不是文件大小；用它对进度才不会超过 100%
                        callback(filename, monitor.len, monitor.bytes_read)

                body = MultipartEncoderMonitor(encoder, _cb)
            else:
                body = encoder
            headers = dict(self._headers)
            headers["Content-Type"] = body.content_type
            r = self._post(body, url=URL_UPLOAD,
                           timeout=max(self.timeout, 3600), headers=headers)

        if not r.content:
            raise LanzouError(FAILED, "上传接口返回空响应")
        d = r.json()
        if d.get("zt") != 1:
            raise LanzouError(FAILED, "上传失败: %s" % d.get("info"))
        item = (d.get("text") or [{}])[0]
        return {
            "id": int(item.get("id") or 0),
            "name": item.get("name_all") or filename,
            "size": item.get("size") or _size_str(os.path.getsize(file_path)),
            "share_url": (item.get("is_newd") or "") + "/" + (item.get("f_id") or "")
            if item.get("f_id") else "",
        }

    # ---------------- 下载

    @staticmethod
    def _page_name(html):
        m = re.search(r"<title>(.*?)</title>", html, re.S)
        if m:
            t = re.sub(r"\s*-\s*蓝奏云$", "", m.group(1).strip()).strip()
            if t and "蓝奏云" not in t:
                return t
        m = re.search(r"font-size:\s*30px;[^>]*>([^<]+)<", html)
        return m.group(1).strip() if m else None

    @staticmethod
    def _page_size(html):
        m = re.search(r"文件大小[:：]\s*(?:</span>)?\s*([\d.]+\s*[KMGB]+)", html)
        return m.group(1).strip() if m else None

    def parse_share_page(self, share_url, pwd=""):
        """解析文件分享页，返回下载参数与元信息

        返回 dict，mode 字段区分两条链路：
          - 'sign'    ：常规分享页，参数 sign / kd / file_id
          - 'websign' ：文件夹分享落地页（?webpage= → iframe /fn?），
                        参数 wp_sign / ajaxdata / kd / domain
        """
        page = self._get_html(share_url)
        page.encoding = "utf-8"
        html = page.text
        host = "https://" + urlsplit(share_url).hostname

        # 只有密码框、没有下载参数 → 需要先提交提取码
        if "var isngis" not in html and 'id="pwd"' in html and "/fn?" not in html:
            if not pwd:
                raise LanzouError(LACK_PASSWORD, "该文件需要提取码，请用 -p 指定")
            m = re.search(r"action=['\"]([^'\"]+)['\"]", html)
            post_url = m.group(1) if m else share_url
            if post_url.startswith("/"):
                post_url = host + post_url
            r = self.session.post(post_url, {"pwd": pwd}, headers=self._headers,
                                  timeout=self.timeout, verify=False)
            r.encoding = "utf-8"
            html = r.text

        meta = {"name": self._page_name(html), "size": self._page_size(html)}

        # 链路一：文件夹分享落地页 → iframe /fn? → websign 参数
        m = re.search(r'src="(/fn\?[^"]+)"', html)
        if m:
            ifr = self._get_html(host + m.group(1))
            ifr.encoding = "utf-8"
            ih = ifr.text
            wp = re.search(r"var wp_sign = '([^']+)'", ih)
            ad = re.search(r"var ajaxdata = '([^']+)'", ih)
            kd = re.search(r"var kdns =(\d+)", ih)
            dom = re.search(r"var domain1 = '([^']+)'", ih)
            if wp and ad and dom:
                meta.update({"mode": "websign", "wp_sign": wp.group(1),
                             "ajaxdata": ad.group(1),
                             "kd": kd.group(1) if kd else "1",
                             "domain": dom.group(1)})
                return meta

        # 链路二：常规分享页
        sign = re.search(r"var isngis = '([^']+)'", html)
        kd = re.search(r"var kdns =(\d+)", html)
        fid = re.search(r"ajaxfile\.php\?file=(\d+)", html)
        if not (sign and fid):
            raise LanzouError(PASSWORD_ERROR if pwd else URL_INVALID,
                              "解析分享页失败：没找到下载参数" +
                              ("（提取码可能不对）" if pwd else ""))
        meta.update({"mode": "sign", "sign": sign.group(1),
                     "kd": kd.group(1) if kd else "1", "file_id": fid.group(1)})
        return meta

    def direct_url(self, share_url, pwd=""):
        """取下载直链 -> {'name','durl'}"""
        meta = self.parse_share_page(share_url, pwd)
        headers = dict(self._headers)
        headers["Referer"] = share_url
        if meta["mode"] == "websign":
            api, data = meta["domain"], {
                "action": "downprocess", "websignkey": meta["ajaxdata"],
                "signs": meta["ajaxdata"], "sign": meta["wp_sign"],
                "websign": "2", "kd": meta["kd"], "ves": 1,
            }
        else:
            api = URL_AJAXFILE + "?file=" + meta["file_id"]
            data = {"action": "downprocess", "sign": meta["sign"],
                    "kd": meta["kd"], "p": pwd or ""}
        r = self.session.post(api, data, headers=headers, timeout=self.timeout,
                              verify=False)
        if not r.content:
            raise LanzouError(FAILED, "取直链接口返回空响应")
        d = r.json()
        if str(d.get("zt")) != "1":
            raise LanzouError(PASSWORD_ERROR if pwd else FAILED,
                              "取直链失败: %s" % d.get("inf"))
        durl = d["dom"] + "/file/" + d["url"]
        if meta["mode"] == "websign":
            durl += "&toolsdown"
        return {"name": meta.get("name"), "durl": durl}

    def download(self, share_url, pwd="", save_path="./Download", callback=None,
                 overwrite=False):
        """按分享链接下载文件，返回最终保存路径"""
        info = self.direct_url(share_url, pwd)
        os.makedirs(save_path, exist_ok=True)
        headers = dict(self._headers)
        headers["Referer"] = share_url
        r = self.session.get(info["durl"], headers=headers, stream=True,
                             timeout=max(self.timeout, 600), verify=False)
        if is_challenge(r.content[:1500].decode("utf-8", "ignore")):
            host = urlsplit(r.url).hostname
            self.session.cookies.set("acw_sc__v2", calc_acw_sc__v2(
                r.content.decode("utf-8", "ignore")), domain=host)
            r = self.session.get(info["durl"], headers=headers, stream=True,
                                 timeout=max(self.timeout, 600), verify=False)
        if r.status_code != 200:
            raise LanzouError(NETWORK_ERROR, "下载失败 HTTP %s" % r.status_code)

        cd = r.headers.get("content-disposition") or ""
        m = re.search(r"filename\*?=(?:UTF-8''|\")?([^\";]+)", cd)
        filename = None
        if m:
            from urllib.parse import unquote
            filename = unquote(m.group(1).strip().strip('"'))
        if not filename:
            filename = info["name"] or "download.bin"
        filename = safe_name(os.path.basename(filename))

        path = os.path.join(save_path, filename)
        if os.path.exists(path):
            if overwrite:
                os.remove(path)
            else:
                path = auto_rename(path)

        total = int(r.headers.get("content-length") or 0)
        done = 0
        with open(path, "wb") as fh:
            for chunk in r.iter_content(262144):
                if not chunk:
                    continue
                fh.write(chunk)
                done += len(chunk)
                if callback is not None:
                    callback(filename, total, done)
        return os.path.abspath(path)

    def download_by_id(self, fid, save_path="./Download", callback=None, overwrite=False):
        """按文件 id 下载（自动取分享链接）"""
        s = self.share(fid, is_file=True)
        return self.download(s["url"], s["pwd"], save_path, callback=callback,
                             overwrite=overwrite)

    def download_folder(self, folder_id, save_path="./Download", callback=None,
                        overwrite=False, recursive=False, mkdir=True, failed=None):
        """下载文件夹（先列目录再逐个下）。返回已下载的路径列表"""
        listing = self.list_dir(folder_id)
        out = save_path
        if mkdir:
            name = "folder_%s" % folder_id
            try:
                name = safe_name(self.folder_name(folder_id) or name)
            except LanzouError:
                pass
            out = os.path.join(save_path, name)
        os.makedirs(out, exist_ok=True)
        got = []
        for f in listing["files"]:
            try:
                got.append(self.download_by_id(f["id"], out, callback=callback,
                                               overwrite=overwrite))
            except LanzouError as exc:
                if failed:
                    failed(exc, f)
                else:
                    raise
        if recursive:
            for sub in listing["folders"]:
                got.extend(self.download_folder(sub["id"], out, callback=callback,
                                                overwrite=overwrite, recursive=True,
                                                mkdir=True, failed=failed))
        return got

    def folder_name(self, folder_id):
        d = self._api({"task": 18, "folder_id": folder_id})
        info = d.get("info")
        if isinstance(info, dict):
            return info.get("name") or ""
        return ""

    def folder_url_entries(self, share_url, dir_pwd="", max_pages=100):
        """解析文件夹分享链接，返回条目列表

        文件夹分享页把内容放在 /filemoreajax.php?file=<folder_id>，
        puid / t / k 三个校验参数要从页面 HTML 里抠。
        """
        page = self._get_html(share_url)
        page.encoding = "utf-8"
        html = page.text

        def grab(pat, default=""):
            m = re.search(pat, html)
            return m.group(1) if m else default

        fid = grab(r"filemoreajax\.php\?file=(\d+)")
        puid = grab(r"'puid':'([^']+)'")
        t = grab(r"var ib6x0h = '(\d+)'")
        k = grab(r"var _h592a = '(\w+)'")
        uid = grab(r"'uid':'(\d+)'")
        if not fid:
            raise LanzouError(URL_INVALID, "这不是文件夹分享页，或页面结构已变")
        host = "https://" + urlsplit(share_url).hostname
        entries = []
        for pg in range(1, max_pages + 1):
            data = {"lx": 2, "fid": fid, "uid": uid, "puid": puid, "pg": pg,
                    "rep": "0", "t": t, "k": k, "up": 1, "ls": 1, "pwd": dir_pwd or ""}
            r = self.session.post(host + "/filemoreajax.php?file=" + fid, data,
                                  headers=dict(self._headers, Referer=share_url),
                                  timeout=self.timeout, verify=False)
            if not r.content:
                break
            d = r.json()
            if str(d.get("zt")) != "1":
                if pg == 1:
                    raise LanzouError(PASSWORD_ERROR if dir_pwd else FAILED,
                                      "取文件夹内容失败: %s" % d.get("info"))
                break
            batch = d.get("text") or []
            if not batch:
                break
            for it in batch:
                code = it.get("id") or ""
                name = it.get("name_all") or ""
                if it.get("t") == 1:  # 推广条目
                    continue
                if not code or "/" in code:
                    continue
                entries.append({
                    "url": "%s/%s" % (host, code),
                    "name": name,
                    "size": it.get("size") or "",
                    "time": it.get("time") or "",
                })
        return entries

    def download_folder_url(self, share_url, dir_pwd="", save_path="./Download",
                            callback=None, overwrite=False):
        """按文件夹分享链接下载全部文件，返回已下载路径列表"""
        entries = self.folder_url_entries(share_url, dir_pwd)
        os.makedirs(save_path, exist_ok=True)
        got = []
        for ent in entries:
            got.append(self.download(ent["url"], dir_pwd, save_path,
                                     callback=callback, overwrite=overwrite))
        return got


# ---------------------------------------------------------------- cookie 解析

def parse_cookie(raw):
    """支持 'a=1; b=2' 或 JSON 两种形式"""
    raw = (raw or "").strip()
    if not raw:
        raise LanzouError(ID_ERROR, "cookie 为空")
    if raw.startswith("{"):
        import json
        try:
            return json.loads(raw)
        except ValueError as exc:
            raise LanzouError(ID_ERROR, "cookie JSON 解析失败: %s" % exc)
    out = {}
    for part in raw.replace("\n", ";").split(";"):
        part = part.strip()
        if "=" in part:
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
    if not out.get("ylogin"):
        raise LanzouError(ID_ERROR,
                          "cookie 缺少 ylogin 字段。请从浏览器 F12 → Network → Request Headers "
                          "里复制整行 Cookie（必须含 ylogin 和 phpdisk_info）")
    return out
