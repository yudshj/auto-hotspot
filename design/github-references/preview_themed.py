"""Illustrate themed palettes; these are not screenshots of the user's Pixel."""
from pathlib import Path
from lxml import etree as E
ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
A = '{http://schemas.android.com/apk/res/android}'
mono = E.parse(str(ROOT/'app/src/main/res/drawable/ic_launcher_monochrome.xml'))
fg = E.parse(str(ROOT/'app/src/main/res/drawable/ic_launcher_foreground.xml'))
colors = {e.get('name'):e.text for e in E.parse(str(ROOT/'app/src/main/res/values/icon_colors.xml')).getroot()}
items = [
    ('浅色主题 · 示例', '#EADDFF', '#21005D', True),
    ('暖色主题 · 示例', '#F3E1BB', '#261E07', True),
    ('深色主题 · 示例', '#314A62', '#CEE5FF', True),
    ('未开启主题图标', colors['ic_launcher_background'], colors['ic_launcher_foreground'], False),
]
parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="1200" viewBox="0 0 1200 1200">', '<rect width="1200" height="580" fill="#F7F8FA"/>','<g font-family="Helvetica Neue, Hiragino Sans GB, sans-serif" fill="#293342">','<text x="56" y="66" font-size="18" fill="#77828F">PIXEL / THEMED ICON</text>','<text x="56" y="118" font-size="36">同一个图形，跟随系统主题着色</text>']
for i,(label,bg,color,themed) in enumerate(items):
    x=60+i*290
    parts.append(f'<circle cx="{x+105}" cy="302" r="105" fill="{bg}"/>')
    parts.append(f'<svg x="{x+40.385}" y="237.385" width="129.23" height="129.23" viewBox="0 0 256 256">')
    for p in (mono if themed else fg).findall('.//path'):
        parts.append(f'<path fill="{color}" opacity="{p.get(A+"fillAlpha", "1")}" d="{p.get(A+"pathData")}"/>')
    parts.append('</svg>')
    parts.append(f'<text x="{x+4}" y="451" font-size="21">{label}</text>')
parts += ['<text x="56" y="528" font-size="18" fill="#77828F">前三项为配色示意；实际颜色由 Pixel 壁纸、深浅模式和启动器决定。</text>','</g></svg>']
(OUT/'themed-preview.svg').write_text(''.join(parts))
# Validate resource structure and preservation of upstream geometry.
assert mono.find('.//path').get(A+'pathData') == fg.findall('.//path')[1].get(A+'pathData')
assert E.parse(str(ROOT/'app/src/main/res/drawable-v33/ic_launcher.xml')).getroot().find('monochrome').get(A+'drawable') == '@drawable/ic_launcher_monochrome'
for file in (ROOT/'app/src/main/res').glob('**/*.xml'):
    E.parse(str(file))
print('Validated XML, monochrome wiring and identical signal geometry; generated palette study.')
