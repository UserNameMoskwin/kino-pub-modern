"""Рисует значок и баннер приложения: «kino» белым и «pub» на красной плашке.

Запускать из корня проекта: python device/icons.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

RES = 'app/src/main/res/'
FONT = 'C:/Windows/Fonts/arialbd.ttf'
RED = (229, 9, 20)
BG = (16, 16, 20)


def ink(text, font):
    """Границы нарисованных точек слова относительно начала строки на базовой линии."""
    pad = font.size * 2
    mask = Image.new('L', (pad * 6, pad * 3), 0)
    ImageDraw.Draw(mask).text((pad, pad * 2), text, font=font, fill=255, anchor='ls')
    left, top, right, bottom = mask.point(lambda v: 255 if v > 127 else 0).getbbox()
    return left - pad, top - pad * 2, right - pad, bottom - pad * 2


def logo(draw, cx, cy, size, stacked=False):
    """Знак с центром в (cx, cy). Плашка строится вокруг самих букв «pub» с равными полями
    сверху и снизу, поэтому слово стоит в ней ровно посередине."""
    font = ImageFont.truetype(FONT, size)
    mx, my = size * 0.34, size * 0.26
    gap = size * 0.20
    # Границы букв относительно начала строки на базовой линии. Меряются по самим
    # нарисованным точкам: расчётные границы шрифта отличаются от них на пару точек, и
    # слово в плашке получалось сдвинутым вбок.
    kl, kt, kr, kb = ink('kino', font)
    pl, pt, pr, pb = ink('pub', font)
    box_w, box_h = (pr - pl) + mx * 2, (pb - pt) + my * 2
    if stacked:
        total = (kb - kt) + gap + box_h
        top = cy - total / 2
        draw.text((cx - (kl + kr) / 2, top - kt), 'kino', font=font, fill='white', anchor='ls')
        bx, by = cx - box_w / 2, top + (kb - kt) + gap
    else:
        total = (kr - kl) + gap + box_w
        left = cx - total / 2
        bx, by = left + (kr - kl) + gap, cy - box_h / 2
    # Базовая линия «pub»: буквы с полем my от верха плашки.
    baseline = by + my - pt
    if not stacked:
        draw.text((left - kl, baseline), 'kino', font=font, fill='white', anchor='ls')
    draw.rounded_rectangle((bx, by, bx + box_w, by + box_h), radius=size * 0.22, fill=RED)
    draw.text((bx + mx - pl, baseline), 'pub', font=font, fill='white', anchor='ls')


def save(folder, name, w, h, size, stacked):
    # Рисуется вчетверо крупнее и уменьшается: края букв получаются гладкими.
    k = 4
    im = Image.new('RGB', (w * k, h * k), BG)
    logo(ImageDraw.Draw(im), w * k / 2, h * k / 2, size * k, stacked)
    os.makedirs(RES + folder, exist_ok=True)
    im.resize((w, h), Image.LANCZOS).save(RES + folder + '/' + name + '.png', optimize=True)


save('drawable-xhdpi', 'banner', 320, 180, 56, False)
save('drawable-xxxhdpi', 'banner', 640, 360, 112, False)
save('drawable-xhdpi', 'icon', 96, 96, 27, True)
save('drawable-xxxhdpi', 'icon', 192, 192, 54, True)
