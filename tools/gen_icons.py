#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成一套规范的应用图标：adaptive(108dp) + 旧版圆角/圆形 PNG。"""
import os
from PIL import Image, ImageDraw

BG = (21, 101, 192, 255)      # #1565C0
FG = (255, 255, 255, 255)
SS = 4                         # 超采样倍数

RES = "/workspace/LanzouCloud/app/src/main/res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def draw_mark(d, cx, cy, size, color):
    """在 (cx,cy) 为中心、边长 size 的方框内画「云 + 下箭头」。"""
    u = size / 100.0

    def R(x0, y0, x1, y1, r=0):
        d.rounded_rectangle([cx + (x0 - 50) * u, cy + (y0 - 50) * u,
                             cx + (x1 - 50) * u, cy + (y1 - 50) * u],
                            radius=r * u, fill=color)

    def C(x, y, r):
        d.ellipse([cx + (x - r - 50) * u, cy + (y - r - 50) * u,
                   cx + (x + r - 50) * u, cy + (y + r - 50) * u], fill=color)

    # 云：底座 + 三个圆
    R(16, 40, 84, 62, r=11)
    C(34, 40, 15)
    C(52, 31, 20)
    C(72, 41, 13)

    # 向下箭头（从云底穿出）
    R(43, 60, 57, 74, r=4)
    d.polygon([(cx + (28 - 50) * u, cy + (68 - 50) * u),
               (cx + (72 - 50) * u, cy + (68 - 50) * u),
               (cx + (50 - 50) * u, cy + (92 - 50) * u)], fill=color)


def rounded_icon(px):
    """旧版图标：圆角矩形底 + 前景。"""
    S = px * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = int(S * 0.22)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=BG)
    draw_mark(d, S / 2, S / 2, S * 0.80, FG)
    return img.resize((px, px), Image.LANCZOS)


def round_icon(px):
    S = px * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, S - 1, S - 1], fill=BG)
    draw_mark(d, S / 2, S / 2, S * 0.78, FG)
    return img.resize((px, px), Image.LANCZOS)


def foreground(px):
    """自适应图标前景：透明底，内容限制在中心 72/108 安全区附近。"""
    S = px * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_mark(d, S / 2, S / 2, S * (72.0 / 108.0) * 1.08, FG)
    return img.resize((px, px), Image.LANCZOS)


def main():
    for name, scale in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        rounded_icon(int(48 * scale)).save(os.path.join(folder, "ic_launcher.png"))
        round_icon(int(48 * scale)).save(os.path.join(folder, "ic_launcher_round.png"))
        # adaptive 前景画布 108dp
        foreground(int(108 * scale)).save(os.path.join(folder, "ic_launcher_foreground.png"))
    print("图标已生成")


if __name__ == "__main__":
    main()
