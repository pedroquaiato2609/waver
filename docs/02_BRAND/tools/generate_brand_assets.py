# Generates SVG logos, app icons and favicons from the official WAVER symbol geometry.
# Geometry was fitted to logos/waver_symbol_dark.png (99.8% pixel match). Run from docs/02_BRAND:  python tools/generate_brand_assets.py
from PIL import Image, ImageDraw
import os
FOREST='#0F3D2E'; MINERAL='#2E7D6B'; SAGE='#A7C4B0'; OFF='#F7F6F2'; GRAPH='#2D2D2D'; LIME='#C7E36B'
# Symbol polygons fitted to the official PNG (origin = symbol bbox top-left), size 436 x 203
L=[(0,7),(151,0),(215,81),(113,203)]
M=[(174,63),(260,68),(345,14),(248,203)]
R=[(270,85),(435,0),(338,190)]
W,H=436,203
def pts(p,dx=0,dy=0,s=1): return ' '.join(f'{x*s+dx:g},{y*s+dy:g}' for x,y in p)
def symbol_svg(outer,mid,title):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" role="img" aria-labelledby="t">
  <title id="t">{title}</title>
  <polygon points="{pts(L)}" fill="{outer}"/>
  <polygon points="{pts(M)}" fill="{mid}"/>
  <polygon points="{pts(R)}" fill="{outer}"/>
</svg>
'''
def mono_svg():
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" role="img" aria-labelledby="t">
  <title id="t">WAVER</title>
  <!-- Monochrome: uses currentColor; middle facet at 55% opacity keeps the depth cue -->
  <polygon points="{pts(L)}" fill="currentColor"/>
  <polygon points="{pts(M)}" fill="currentColor" fill-opacity="0.55"/>
  <polygon points="{pts(R)}" fill="currentColor"/>
</svg>
'''
def lockup_svg(outer,mid,text,bg=None):
    # Lockup proportions follow waver_primary_*.png: symbol height ~ 0.43 of 600px canvas; wordmark cap height ~ 0.17
    s=1.0; sx=0; sy=0
    wx=W+60  # gap symbol -> wordmark
    bgrect=f'<rect width="100%" height="100%" fill="{bg}"/>' if bg else ''
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="-40 -40 {W+700} {H+80}" role="img" aria-labelledby="t">
  <title id="t">WAVER - Ideias em movimento</title>
  {bgrect.replace('width="100%" height="100%"', f'x="-40" y="-40" width="{W+700}" height="{H+80}"')}
  <polygon points="{pts(L)}" fill="{outer}"/>
  <polygon points="{pts(M)}" fill="{mid}"/>
  <polygon points="{pts(R)}" fill="{outer}"/>
  <!-- Wordmark set in Inter (brand typeface). Convert to outlines in the final master file. -->
  <text x="{wx}" y="130" font-family="Inter, 'Helvetica Neue', Arial, sans-serif" font-size="128" font-weight="400" letter-spacing="38" fill="{text}">WAVER</text>
  <text x="{wx+4}" y="196" font-family="Inter, 'Helvetica Neue', Arial, sans-serif" font-size="30" font-weight="500" letter-spacing="9" fill="{text}">IDEIAS EM MOVIMENTO</text>
</svg>
'''
def icon_svg(bg,outer,mid,title):
    size=512; r=112; sw=300; s=sw/W; dx=(size-sw)/2; dy=(size-H*s)/2
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" role="img" aria-labelledby="t">
  <title id="t">{title}</title>
  <rect width="{size}" height="{size}" rx="{r}" fill="{bg}"/>
  <polygon points="{pts(L,dx,dy,s)}" fill="{outer}"/>
  <polygon points="{pts(M,dx,dy,s)}" fill="{mid}"/>
  <polygon points="{pts(R,dx,dy,s)}" fill="{outer}"/>
</svg>
'''
def icon_png(path,bg,outer,mid,size=512,ss=4,border=None):
    S=size*ss; im=Image.new('RGBA',(S,S),(0,0,0,0)); d=ImageDraw.Draw(im)
    d.rounded_rectangle([0,0,S-1,S-1],radius=int(112/512*S),fill=bg,outline=border,width=(2*ss if border else 0))
    sw=300/512*S; s=sw/W; dx=(S-sw)/2; dy=(S-H*s)/2
    for p,c in ((L,outer),(M,mid),(R,outer)):
        d.polygon([(x*s+dx,y*s+dy) for x,y in p],fill=c)
    im.resize((size,size),Image.LANCZOS).save(path)
w=lambda p,c: open(p,'w',encoding='utf-8').write(c)
w('logos/svg/waver_symbol_light_bg.svg',symbol_svg(FOREST,MINERAL,'WAVER'))
w('logos/svg/waver_symbol_dark_bg.svg',symbol_svg(OFF,SAGE,'WAVER'))
w('logos/svg/waver_symbol_mono.svg',mono_svg())
w('logos/svg/waver_lockup_light_bg.svg',lockup_svg(FOREST,MINERAL,FOREST))
w('logos/svg/waver_lockup_dark_bg.svg',lockup_svg(OFF,SAGE,OFF,bg=FOREST))
apps={'control':(FOREST,OFF,SAGE,'WAVER Control'),'partner':(OFF,FOREST,MINERAL,'WAVER Partner'),'workspace':(MINERAL,OFF,LIME,'WAVER Workspace')}
for k,(bg,o,m,t) in apps.items():
    w(f'icons/svg/waver_{k}_app_icon.svg',icon_svg(bg,o,m,t))
    icon_png(f'icons/waver_{k}_app_icon.png',bg,o,m,border=('#D9DED8' if k=='partner' else None))
# favicons
for n in (16,32,48,180):
    icon_png(f'icons/waver_favicon_{n}.png',FOREST,OFF,SAGE,size=n,ss=8)
print('ok')
