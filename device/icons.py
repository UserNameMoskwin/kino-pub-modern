"""Рисует знак приложения: «KINO» белым и «PUB» на красной плашке, шрифт Inter Black.

Запускать из корня проекта: python device/icons.py
Получаются баннер и значок для экрана телевизора и знак для бокового меню приложения.
Шрифт лежит рядом, в device/fonts (лицензия OFL).
"""
import os
from PIL import Image, ImageDraw, ImageFont

RES = 'app/src/main/res/'
FONT = 'device/fonts/Inter-Variable.ttf'
RED = (0xE0, 0x02, 0x06, 255)
WHITE = (255, 255, 255, 255)
BG = (0x0F, 0x0F, 0x0F, 255)
SIZE = 400  # кегль, которым знак рисуется до уменьшения


def font():
    f = ImageFont.truetype(FONT, SIZE)
    f.set_variation_by_axes([32, 900])  # оптический размер для крупного текста, начертание Black
    return f


def ink(text, f):
    """Границы нарисованных точек слова относительно начала строки на базовой линии."""
    pad = SIZE * 2
    mask = Image.new('L', (pad * 6, pad * 3), 0)
    ImageDraw.Draw(mask).text((pad, pad * 2), text, font=f, fill=255, anchor='ls')
    left, top, right, bottom = mask.point(lambda v: 255 if v > 127 else 0).getbbox()
    return left - pad, top - pad * 2, right - pad, bottom - pad * 2


def logo(stacked):
    """Знак на прозрачном фоне, обрезанный по краям. Плашка строится вокруг самих букв
    «PUB» с равными полями, поэтому слово стоит в ней ровно посередине. [stacked] —
    слова друг под другом, для квадратного значка."""
    f = font()
    kl, kt, kr, kb = ink('KINO', f)
    pl, pt, pr, pb = ink('PUB', f)
    cap = kb - kt  # высота заглавной буквы: от неё считаются все отступы
    mx, my, gap = cap * 0.40, cap * 0.27, cap * 0.18
    box_w, box_h = (pr - pl) + mx * 2, (pb - pt) + my * 2
    canvas = Image.new('RGBA', (SIZE * 8, SIZE * 5), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    x0, y0 = SIZE, SIZE
    if stacked:
        width = max(kr - kl, box_w)
        draw.text((x0 + (width - (kr - kl)) / 2 - kl, y0 - kt), 'KINO', font=f, fill=WHITE, anchor='ls')
        bx, by = x0 + (width - box_w) / 2, y0 + cap + gap * 1.4
    else:
        bx, by = x0 + (kr - kl) + gap, y0
        draw.text((x0 - kl, by + my - kt), 'KINO', font=f, fill=WHITE, anchor='ls')
    draw.rounded_rectangle((bx, by, bx + box_w, by + box_h), radius=box_h * 0.24, fill=RED)
    draw.text((bx + mx - pl, by + my - pt), 'PUB', font=f, fill=WHITE, anchor='ls')
    return canvas.crop(canvas.getbbox())


def save(folder, name, w, h, stacked, fill, background=BG):
    """Картинка w×h со знаком посередине; [fill] — какую долю ширины он занимает."""
    mark = logo(stacked)
    scale = min(w * fill / mark.width, h * fill / mark.height)
    mark = mark.resize((round(mark.width * scale), round(mark.height * scale)), Image.LANCZOS)
    im = Image.new('RGBA', (w, h), background)
    im.alpha_composite(mark, ((w - mark.width) // 2, (h - mark.height) // 2))
    os.makedirs(RES + folder, exist_ok=True)
    im.save(RES + folder + '/' + name + '.png', optimize=True)


def save_mark(folder, name, height):
    """Знак без фона и полей заданной высоты — для бокового меню."""
    mark = logo(False)
    mark = mark.resize((round(mark.width * height / mark.height), height), Image.LANCZOS)
    os.makedirs(RES + folder, exist_ok=True)
    mark.save(RES + folder + '/' + name + '.png', optimize=True)


for folder, k in (('drawable-xhdpi', 1), ('drawable-xxxhdpi', 2)):
    save(folder, 'banner', 320 * k, 180 * k, False, 0.68)  # по весу как у соседних значков на экране телевизора
    save(folder, 'icon', 96 * k, 96 * k, True, 0.70)
    save_mark(folder, 'logo', 30 * 2 * k)  # 30dp в меню
