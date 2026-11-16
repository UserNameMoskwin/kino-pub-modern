import os
from PIL import Image, ImageDraw, ImageFont

RES = 'app/src/main/res/'
FONT = 'C:/Windows/Fonts/arialbd.ttf'
RED = (229, 9, 20)
BG = (16, 16, 20)


def logo(draw, cx, cy, size, stacked=False):
    """kino белым и pub на красной плашке; (cx, cy) — центр знака."""
    font = ImageFont.truetype(FONT, size)
    pad = round(size * 0.18)
    gap = round(size * 0.12)
    kino = draw.textbbox((0, 0), 'kino', font=font)
    pub = draw.textbbox((0, 0), 'pub', font=font)
    asc, desc = font.getmetrics()
    line = asc + desc
    kw, pw = kino[2] - kino[0], pub[2] - pub[0]
    box_w, box_h = pw + pad * 2, line + round(size * 0.06)
    if stacked:
        total = line + gap + box_h
        top = cy - total / 2
        draw.text((cx - kw / 2 - kino[0], top), 'kino', font=font, fill='white')
        bx, by = cx - box_w / 2, top + line + gap
    else:
        total = kw + gap + box_w
        left = cx - total / 2
        draw.text((left - kino[0], cy - line / 2), 'kino', font=font, fill='white')
        bx, by = left + kw + gap, cy - box_h / 2
    draw.rounded_rectangle((bx, by, bx + box_w, by + box_h), radius=round(size * 0.16), fill=RED)
    draw.text((bx + pad - pub[0], by + (box_h - line) / 2), 'pub', font=font, fill='white')


def save(folder, name, w, h, size, stacked):
    # Рисуется вчетверо крупнее и уменьшается: края букв получаются гладкими.
    k = 4
    im = Image.new('RGB', (w * k, h * k), BG)
    logo(ImageDraw.Draw(im), w * k / 2, h * k / 2, size * k, stacked)
    os.makedirs(RES + folder, exist_ok=True)
    im.resize((w, h), Image.LANCZOS).save(RES + folder + '/' + name + '.png', optimize=True)


save('drawable-xhdpi', 'banner', 320, 180, 62, False)
save('drawable-xxxhdpi', 'banner', 640, 360, 124, False)
save('drawable-xhdpi', 'icon', 96, 96, 30, True)
save('drawable-xxxhdpi', 'icon', 192, 192, 60, True)
