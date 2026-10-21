from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from lxml import etree

ROOT = Path(__file__).resolve().parent
etree.parse(str(ROOT / 'auto-hotspot.svg'))
icon = Image.open(ROOT / 'auto-hotspot.svg.png').convert('RGBA')
# Quick Look renders onto white; restore the tile's vector outline as alpha.
mask = Image.new('L', (2048, 2048))
ImageDraw.Draw(mask).rounded_rectangle((0, 0, 2047, 2047), radius=464, fill=255)
icon.putalpha(mask.resize(icon.size, Image.Resampling.LANCZOS))
icon.save(ROOT / 'auto-hotspot.png')
canvas = Image.new('RGB', (1440, 1020), '#F4F8F6')
draw = ImageDraw.Draw(canvas)
font_path = '/System/Library/Fonts/Hiragino Sans GB.ttc'
def text(x, y, value, size=24, color='#153D37'):
    draw.text((x, y), value, font=ImageFont.truetype(font_path, size), fill=color)
def paste_icon(x, y, size, shadow=False):
    tile = icon.resize((size, size), Image.Resampling.LANCZOS)
    if shadow:
        layer = Image.new('RGBA', canvas.size)
        a = Image.new('RGBA', (size, size), '#004A3C')
        a.putalpha(tile.getchannel('A').point(lambda p: int(p * .14)))
        layer.alpha_composite(a, (x, y + 14))
        layer = layer.filter(ImageFilter.GaussianBlur(22))
        canvas.paste(layer, (0, 0), layer)
    canvas.paste(tile, (x, y), tile)

text(64, 39, 'AUTO HOTSPOT', 20, '#008779')
text(64, 79, '热点自动关 · 图标设计提案', 38)
text(1070, 91, '01 / 信号 × 开关', 22, '#58776E')
draw.rounded_rectangle((48, 170, 1392, 752), radius=32, fill='#FFFFFF')
draw.rounded_rectangle((737, 170, 1392, 752), radius=32, fill='#102A26')
text(86, 200, '浅色背景', 19, '#6B837B')
text(779, 200, '深色背景', 19, '#93B8A9')
paste_icon(210, 272, 350, True)
paste_icon(893, 272, 350, True)
text(315, 655, '热点自动关', 24)
text(998, 655, '热点自动关', 24, '#E8F8EF')
text(69, 794, '小尺寸识别', 22)
for x, size in [(80, 72), (202, 48), (309, 32)]:
    paste_icon(x, 850 + (72-size)//2, size)
    text(x, 938, f'{size}px', 16, '#6B837B')
text(486, 808, '无线信号 + 电源开关', 24)
text(486, 855, '青绿渐变 / 薄荷高光 / 圆润几何', 20, '#5F7A71')
text(486, 905, '独立 SVG 矢量稿，尚未替换应用图标', 20, '#5F7A71')
for x, color in [(1124, '#11AD9D'), (1198, '#005C58'), (1272, '#B4FFE5')]:
    draw.rounded_rectangle((x, 848, x+54, 902), radius=16, fill=color)
canvas.save(ROOT / 'preview.png')
print('Validated SVG and generated transparent icon + preview sheet.')
