"""Export the selected Phosphor Broadcast design to Android VectorDrawable.

The 256-unit upstream icon occupies 44.31dp of the 108dp adaptive layer.
After a launcher's nominal 72dp mask, this matches the approved 61.54% SVG
viewport / tile ratio. Notification resources are deliberately untouched.
"""
from pathlib import Path
from lxml import etree as E

ROOT = Path(__file__).resolve().parents[2]
REF = ROOT / 'design/github-references'
RES = ROOT / 'app/src/main/res'
A = 'http://schemas.android.com/apk/res/android'
S = 'http://www.w3.org/2000/svg'
source = E.parse(str(REF / 'phosphor-broadcast.svg'))
paths = source.findall(f'{{{S}}}path')
assert len(paths) == 2

for name, monochrome in [('ic_launcher_foreground', False), ('ic_launcher_monochrome', True)]:
    vector = E.Element('vector', nsmap={'android': A})
    for key, value in dict(width='108dp', height='108dp', viewportWidth='108', viewportHeight='108').items():
        vector.set(f'{{{A}}}{key}', value)
    vector.append(E.Comment(' Phosphor Broadcast, Copyright (c) 2023 Phosphor Icons, MIT. See assets/licenses/phosphor-icons.txt. '))
    group = E.SubElement(vector, 'group')
    for key, value in dict(scaleX='0.173076923', scaleY='0.173076923', translateX='31.846153846', translateY='31.846153846').items():
        group.set(f'{{{A}}}{key}', value)
    for path in paths:
        # Omit translucent center in monochrome mode for a crisp alpha mask.
        if monochrome and path.get('opacity'):
            continue
        p = E.SubElement(group, 'path')
        p.set(f'{{{A}}}fillColor', '#FF000000' if monochrome else '@color/ic_launcher_foreground')
        if path.get('opacity'):
            p.set(f'{{{A}}}fillAlpha', path.get('opacity'))
        p.set(f'{{{A}}}pathData', path.get('d'))
    (RES / 'drawable' / (name+'.xml')).write_bytes(E.tostring(vector, pretty_print=True, encoding='utf-8', xml_declaration=True))

licenses = ROOT / 'app/src/main/assets/licenses'
licenses.mkdir(parents=True, exist_ok=True)
license_text = (REF/'phosphor-LICENSE.txt').read_text()
(licenses/'phosphor-icons.txt').write_text('Phosphor Icons — Broadcast (duotone)\nSource: https://github.com/phosphor-icons/core/blob/main/assets/duotone/broadcast-duotone.svg\nChanges: color, adaptive-icon padding; translucent center omitted in monochrome mask.\n\n'+license_text)

# Build a geometry preview from the exact Android paths, not a screenshot.
def symbol(x,y,size,mono=False):
    body = ''.join(E.tostring(p).decode().replace('ns0:', '').replace(':ns0', '') for p in paths if not (mono and p.get('opacity')))
    return f'<svg x="{x}" y="{y}" width="{size}" height="{size}" viewBox="0 0 256 256" fill="#12674E">{body}</svg>'
def icon(x,y,size,circle=False,mono=False):
    bg='#D4F7E9' if not mono else '#DCEAE3'
    shape=f'<circle cx="{x+size/2}" cy="{y+size/2}" r="{size/2}" fill="{bg}"/>' if circle else f'<rect x="{x}" y="{y}" width="{size}" height="{size}" rx="{size*0.23}" fill="{bg}"/>'
    s=size*8/13
    return shape+symbol(x+(size-s)/2,y+(size-s)/2,s,mono)
preview = f'''<svg xmlns="{S}" width="1200" height="1200" viewBox="0 0 1200 1200">
<rect width="1200" height="760" fill="#F6F8F7"/>
<g font-family="Helvetica Neue, Hiragino Sans GB, sans-serif" fill="#244337">
<text x="64" y="62" font-size="18" fill="#658879">02 / SELECTED DESIGN</text>
<text x="64" y="114" font-size="36">热点自动关 · 信号扩散</text>
<rect x="48" y="158" width="1104" height="508" rx="32" fill="#FFFFFF"/>
{icon(112,214,350)}
<text x="220" y="610" font-size="26">热点自动关</text>
{icon(588,218,176,True)}
<text x="628" y="438" font-size="20">圆形裁切</text>
{icon(892,218,176,False,True)}
<text x="912" y="438" font-size="20">单色主题示意</text>
{icon(612,498,64)}{icon(730,506,48)}{icon(834,514,32)}
<text x="612" y="601" font-size="18" fill="#658879">64 / 48 / 32 px</text>
<text x="64" y="720" font-size="19" fill="#658879">Phosphor Broadcast · MIT / 薄荷绿底色 + 深绿波纹 / Android 自适应矢量图标</text>
</g></svg>'''
(REF/'selected-preview.svg').write_text(preview)
print('Exported adaptive foreground, monochrome mask, bundled MIT license, and preview.')
