from pathlib import Path
from urllib.request import urlopen
from lxml import etree as E
from html import escape

ROOT = Path(__file__).resolve().parent
NS = 'http://www.w3.org/2000/svg'
items = [
    dict(id='tabler-access-point', name='Tabler', glyph='Access Point', title='极简热点', note='对称、轻盈，最适合工具应用', bg='#24315B', fg='#F0F3FF', license='MIT', repo='tabler/tabler-icons', branch='main', path='icons/outline/access-point.svg', view=24),
    dict(id='phosphor-broadcast', name='Phosphor', glyph='Broadcast · Duotone', title='信号扩散', note='圆心与双层波纹，热点感更强', bg='#D4F7E9', fg='#12674E', license='MIT', repo='phosphor-icons/core', branch='main', path='assets/duotone/broadcast-duotone.svg', view=256),
    dict(id='lucide-wifi-sync', name='Lucide', glyph='WiFi Sync', title='自动连接', note='循环箭头表达自动化，细节较多', bg='#DDEBFF', fg='#2858CB', license='ISC', repo='lucide-icons/lucide', branch='main', path='icons/wifi-sync.svg', view=24),
    dict(id='phosphor-wifi-fill', name='Phosphor', glyph='WiFi High · Fill', title='实心几何', note='更醒目、更简洁，但功能感较弱', bg='#F06F53', fg='#FFF3DD', license='MIT', repo='phosphor-icons/core', branch='main', path='assets/fill/wifi-high-fill.svg', view=256),
]
for i in items:
    base = f"https://raw.githubusercontent.com/{i['repo']}/{i['branch']}/"
    data = urlopen(base+i['path'], timeout=30).read()
    E.fromstring(data)
    (ROOT/(i['id']+'.svg')).write_bytes(data)
    license_path = ROOT / (i['name'].lower()+'-LICENSE.txt')
    if not license_path.exists():
        license_path.write_bytes(urlopen(base+'LICENSE', timeout=30).read())
    i['svg'] = data.decode()

def glyph(i, x, y, size, color):
    root = E.fromstring(i['svg'].encode())
    root.set('x', str(x)); root.set('y', str(y))
    root.set('width', str(size)); root.set('height', str(size))
    return E.tostring(root).decode().replace('currentColor', color)

def txt(x,y,t,size=20,fill='#344056',weight=400):
    return f'<text x="{x}" y="{y}" fill="{fill}" font-size="{size}" font-weight="{weight}">{escape(t)}</text>'

parts = [f'<svg xmlns="{NS}" width="1600" height="910" viewBox="0 0 1600 910"><rect width="1600" height="910" fill="#F4F5F8"/><g font-family="Helvetica Neue, Hiragino Sans GB, sans-serif">']
parts += [txt(64,60,'GITHUB / OPEN-SOURCE ICON STUDY',18,'#6A7490',500), txt(64,116,'热点自动关 · 开源设计参考',40,'#1D2940',600),txt(64,157,'保留原图形，比较四种视觉方向；彩色底板仅为应用图标搭配示意。',21,'#738096')]
for n,i in enumerate(items):
    x=48+n*380
    parts += [f'<rect x="{x}" y="198" width="364" height="600" rx="28" fill="white"/>',txt(x+28,242,f"0{n+1}",18,'#8A93A6',500),txt(x+28,283,i['title'],28,'#1D2940',500)]
    # Palette treatment around an unmodified upstream symbol.
    tilex=x+78; tiley=320
    parts += [f'<rect x="{tilex}" y="{tiley}" width="208" height="208" rx="48" fill="{i["bg"]}"/>',glyph(i,tilex+40,tiley+40,128,i['fg'])]
    parts += [txt(x+28,580,i['name'],23,'#1D2940',500),txt(x+28,614,i['glyph'],18,'#6B768B'),txt(x+28,651,i['note'],17,'#6B768B'), f'<path d="M{x+28} 682H{x+336}" stroke="#EEF0F4"/>', txt(x+28,720,'原图形',16,'#9099A9'),glyph(i,x+102,697,40,'#344056')]
    for k,s in enumerate([48,32]):
        tx=x+205+k*70; ty=695+(48-s)/2
        parts += [f'<rect x="{tx}" y="{ty}" width="{s}" height="{s}" rx="{s*.23}" fill="{i["bg"]}"/>',glyph(i,tx+s*.1923,ty+s*.1923,s*.6154,i['fg'])]
    parts += [txt(x+28,771,i['license']+' LICENSE',13,'#9099A9')]
    tile = f'<svg xmlns="{NS}" width="512" height="512" viewBox="0 0 512 512"><title>{i["name"]} {i["glyph"]} — app treatment study</title><rect width="512" height="512" rx="118" fill="{i["bg"]}"/>{glyph(i,98.46,98.46,315.08,i["fg"])}</svg>'
    (ROOT/(i['id']+'-app.svg')).write_text(tile)
parts += [txt(64,852,'先选图形，再精修配色、比例与 Android 自适应裁切。',21,'#47556C'),txt(64,886,'DESIGN REFERENCES ONLY · 现有应用图标未改动',15,'#8A93A6'),'</g></svg>']
(ROOT/'comparison.svg').write_text(''.join(parts))
md = '# GitHub 开源图标参考\n\n上游图形未改动；应用预览添加了自选底色和圆角底板，并调整了展示尺寸。尚未接入应用资源。\n\n'
for n,i in enumerate(items):
    url=f"https://github.com/{i['repo']}/blob/{i['branch']}/{i['path']}"
    md += f"## {n+1}. {i['name']} / {i['glyph']}\n\n- 来源：{url}\n- 许可证：{i['license']}，完整版权与许可见同目录 `{i['name'].lower()}-LICENSE.txt`。\n- 方向：{i['note']}。\n\n"
md += 'MIT / ISC 均允许修改与分发，分发时需保留相关版权与许可声明。上游 main 分支可能更新；本目录保留了本次下载的原始 SVG。\n'
(ROOT/'SOURCES.md').write_text(md)
print('Downloaded four SVG references and licenses; created comparison and app-treatment SVGs.')
