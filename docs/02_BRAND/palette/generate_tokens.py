import json

def lum(h):
    h = h.lstrip('#'); c = [int(h[i:i+2], 16) / 255 for i in (0, 2, 4)]
    c = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

def cr(a, b):
    la, lb = sorted([lum(a), lum(b)], reverse=True); return (la + 0.05) / (lb + 0.05)

brand = {"forest900": "#0F3D2E", "mineral600": "#2E7D6B", "sage300": "#A7C4B0",
         "offWhite": "#F7F6F2", "graphite": "#2D2D2D", "limeAccent": "#C7E36B"}
prim = {
    "forest": {"950": "#0A2A20", "900": "#0F3D2E", "800": "#154A39", "700": "#1C5A46"},
    "mineral": {"700": "#245F51", "600": "#2E7D6B", "400": "#5FB39D", "200": "#BFDDD3", "50": "#EAF4F0"},
    "sage": {"500": "#74877C", "300": "#A7C4B0", "100": "#DDE8E0"},
    "neutral": {"0": "#FFFFFF", "50": "#F7F6F2", "100": "#EFEEE8", "200": "#E2E1DA", "400": "#9B9D96", "600": "#5E6B64", "900": "#2D2D2D"},
    "lime": {"400": "#C7E36B", "100": "#F1F8DA"},
    "red": {"600": "#B3412F", "300": "#EE907E", "50": "#FBEDEA"},
    "amber": {"700": "#9A6212", "300": "#E0B04F", "50": "#FBF3E2"},
    "blue": {"600": "#2B6A8A", "300": "#6FB0D2", "50": "#E8F2F7"},
    "green": {"600": "#2F7A4F", "300": "#6CC08E", "50": "#E8F4EC"},
}
P = lambda k, s: prim[k][s]
light = {
    "bg.canvas": P("neutral", "50"), "bg.surface": P("neutral", "0"), "bg.surface-muted": P("neutral", "100"), "bg.inverse": P("forest", "900"),
    "text.primary": P("neutral", "900"), "text.secondary": P("neutral", "600"), "text.on-action": P("neutral", "50"), "text.on-inverse": P("neutral", "50"), "text.link": P("mineral", "700"),
    "border.subtle": P("neutral", "200"), "border.decorative": P("sage", "300"), "border.control": P("sage", "500"), "border.focus": P("mineral", "600"),
    "action.primary": P("mineral", "600"), "action.primary-hover": P("mineral", "700"), "action.secondary": P("forest", "900"), "action.selected-bg": P("mineral", "50"),
    "accent.highlight": P("lime", "400"), "accent.highlight-bg": P("lime", "100"),
    "status.success": P("green", "600"), "status.success-bg": P("green", "50"), "status.warning": P("amber", "700"), "status.warning-bg": P("amber", "50"),
    "status.danger": P("red", "600"), "status.danger-bg": P("red", "50"), "status.info": P("blue", "600"), "status.info-bg": P("blue", "50"),
    "nav.bg": P("forest", "900"), "nav.text": P("neutral", "50"), "nav.text-muted": P("sage", "300"), "nav.item-active-bg": P("forest", "800"), "nav.indicator": P("lime", "400"),
    "chart.1": "#2E7D6B", "chart.2": "#2B6A8A", "chart.3": "#9A6212", "chart.4": "#6B5B95", "chart.5": "#5E7A2E", "chart.6": "#5E6B64",
}
dark = {
    "bg.canvas": P("forest", "950"), "bg.surface": P("forest", "900"), "bg.surface-muted": P("forest", "800"), "bg.inverse": P("neutral", "50"),
    "text.primary": P("neutral", "50"), "text.secondary": P("sage", "300"), "text.on-action": P("forest", "950"), "text.on-inverse": P("neutral", "900"), "text.link": P("mineral", "400"),
    "border.subtle": P("forest", "700"), "border.decorative": P("forest", "700"), "border.control": "#6E9483", "border.focus": P("lime", "400"),
    "action.primary": P("mineral", "400"), "action.primary-hover": P("mineral", "200"), "action.secondary": P("neutral", "50"), "action.selected-bg": P("forest", "800"),
    "accent.highlight": P("lime", "400"), "accent.highlight-bg": "#2A3D1C",
    "status.success": P("green", "300"), "status.success-bg": "#173A27", "status.warning": P("amber", "300"), "status.warning-bg": "#3A2E14",
    "status.danger": P("red", "300"), "status.danger-bg": "#3D1F1A", "status.info": P("blue", "300"), "status.info-bg": "#16303D",
    "nav.bg": P("forest", "950"), "nav.text": P("neutral", "50"), "nav.text-muted": P("sage", "300"), "nav.item-active-bg": P("forest", "800"), "nav.indicator": P("lime", "400"),
    "chart.1": "#5FB39D", "chart.2": "#6FB0D2", "chart.3": "#E0B04F", "chart.4": "#B4A3E0", "chart.5": "#C7E36B", "chart.6": "#A7C4B0",
}
checks = [("text.primary", "bg.canvas", 4.5), ("text.primary", "bg.surface", 4.5), ("text.secondary", "bg.surface", 4.5), ("text.secondary", "bg.canvas", 4.5),
          ("text.link", "bg.surface", 4.5), ("text.on-action", "action.primary", 4.5), ("text.on-action", "action.primary-hover", 4.5),
          ("border.control", "bg.surface", 3.0), ("border.focus", "bg.surface", 3.0),
          ("status.success", "bg.surface", 4.5), ("status.warning", "bg.surface", 4.5), ("status.danger", "bg.surface", 4.5), ("status.info", "bg.surface", 4.5),
          ("status.success", "status.success-bg", 4.5), ("status.warning", "status.warning-bg", 4.5), ("status.danger", "status.danger-bg", 4.5), ("status.info", "status.info-bg", 4.5),
          ("nav.text", "nav.bg", 4.5), ("nav.text-muted", "nav.bg", 4.5), ("nav.indicator", "nav.bg", 3.0)] +          [(f"chart.{i}", "bg.surface", 3.0) for i in range(1, 7)]
report = []; ok = True
for name, t in (("light", light), ("dark", dark)):
    for f, b, m in checks:
        r = cr(t[f], t[b]); report.append({"theme": name, "fg": f, "bg": b, "ratio": round(r, 2), "min": m})
        if r < m: ok = False; print('FAIL', name, f, b, round(r, 2), m)
print('all contrast checks pass' if ok else 'SOME CHECKS FAIL')

typo = {"fontFamily": {"sans": "Inter, 'Helvetica Neue', Arial, sans-serif", "mono": "'JetBrains Mono', 'SFMono-Regular', Consolas, monospace"},
        "size": {"xs": "12px", "sm": "13px", "md": "14px", "lg": "16px", "xl": "20px", "2xl": "24px", "3xl": "32px"},
        "lineHeight": {"tight": "1.25", "normal": "1.5"}, "weight": {"regular": "400", "medium": "500", "semibold": "600"},
        "letterSpacing": {"brandCaps": "0.2em"}}
space = {str(k): f"{v}px" for k, v in [(0, 0), (1, 4), (2, 8), (3, 12), (4, 16), (5, 20), (6, 24), (8, 32), (10, 40), (12, 48)]}
radius = {"sm": "4px", "md": "6px", "lg": "10px", "xl": "16px", "pill": "999px"}
shadow = {"sm": "0 1px 2px rgba(15,61,46,0.08)", "md": "0 4px 12px rgba(15,61,46,0.10)", "lg": "0 12px 32px rgba(15,61,46,0.14)"}
motion = {"duration": {"fast": "120ms", "normal": "200ms", "slow": "320ms"}, "easing": {"standard": "cubic-bezier(0.2, 0, 0, 1)"}}
density = {"control-height": {"compact": "28px", "default": "32px", "comfortable": "40px"}, "table-row-height": {"compact": "32px", "default": "40px"}}

out = {"description": "WAVER design tokens. Brand primitives unchanged from v1.1; semantic layer, scales and themes added in v1.2.",
       "version": "1.2.0", "brand": brand, "primitive": prim, "semantic": {"light": light, "dark": dark},
       "typography": typo, "space": space, "radius": radius, "shadow": shadow, "motion": motion, "density": density,
       "usage": {"primary": "brand.forest900", "action": "brand.mineral600", "surface": "brand.offWhite", "text": "brand.graphite", "accent": "brand.limeAccent"},
       "contrastReport": report}
json.dump(out, open('tokens.json', 'w', encoding='utf-8'), indent=2, ensure_ascii=False)

def css_block(t): return '\n'.join(f"  --wv-color-{k.replace('.', '-')}: {v};" for k, v in t.items())
L = ["/* WAVER design tokens v1.2 - generated by generate_tokens.py (run inside palette/). Do not edit by hand. */", ":root {",
     "  /* Brand primitives (v1.1 names kept for compatibility) */"]
L += [f"  --waver-{k}: {v};" for k, v in [("forest-900", brand["forest900"]), ("mineral-600", brand["mineral600"]), ("sage-300", brand["sage300"]),
                                         ("off-white", brand["offWhite"]), ("graphite", brand["graphite"]), ("lime-accent", brand["limeAccent"])]]
L += ["", "  /* Primitive scales */"]
for k, sc in prim.items():
    for s, v in sc.items(): L.append(f"  --wv-{k}-{s}: {v};")
L += ["", "  /* Semantic colors - light theme (default) */", css_block(light), "", "  /* Typography */",
      f"  --wv-font-sans: {typo['fontFamily']['sans']};", f"  --wv-font-mono: {typo['fontFamily']['mono']};"]
L += [f"  --wv-font-size-{k}: {v};" for k, v in typo['size'].items()]
L += [f"  --wv-line-height-{k}: {v};" for k, v in typo['lineHeight'].items()]
L += [f"  --wv-font-weight-{k}: {v};" for k, v in typo['weight'].items()]
L += [f"  --wv-letter-spacing-brand-caps: {typo['letterSpacing']['brandCaps']};", "", "  /* Space, radius, shadow, motion, density */"]
L += [f"  --wv-space-{k}: {v};" for k, v in space.items()]
L += [f"  --wv-radius-{k}: {v};" for k, v in radius.items()]
L += [f"  --wv-shadow-{k}: {v};" for k, v in shadow.items()]
L += [f"  --wv-duration-{k}: {v};" for k, v in motion['duration'].items()]
L += [f"  --wv-easing-standard: {motion['easing']['standard']};",
      f"  --wv-control-height: {density['control-height']['default']};", f"  --wv-table-row-height: {density['table-row-height']['default']};", "}", "",
      "/* Dark theme: default for WAVER Control; used by Workspace chat/agent-execution areas */",
      '[data-theme="dark"] {', css_block(dark), "}", "",
      "/* Compact density (Product Scope 20: fine, compact controls) */",
      '[data-density="compact"] {', f"  --wv-control-height: {density['control-height']['compact']};",
      f"  --wv-table-row-height: {density['table-row-height']['compact']};", "}", ""]
open('tokens.css', 'w', encoding='utf-8').write('\n'.join(L))
print('written')
