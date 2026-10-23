"""Pasa un logo de docs/logo (SVG con rect, circle, polygon, path y un grupo rotado) al
formato vectorial de Android, para el ícono adaptable de Slowtown.

    python3 docs/logo/a_vector.py docs/logo/slowtown.svg > app/src/main/res/drawable/ic_launcher_foreground.xml

El ícono adaptable mide 108 dp, y los launchers nunca recortan los 66 del centro:
el dibujo se achica para que lo más lejano del centro quede a 35 dp.
"""
import math
import re
import sys
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"
MAX_DP = 35.0


def color(c):
    c = c.strip().lstrip("#")
    if len(c) == 3:
        c = "".join(ch * 2 for ch in c)
    return "#FF" + c.upper()


def num(v):
    return float(v)


def rect(e):
    x, y, w, h = (num(e.get(k)) for k in ("x", "y", "width", "height"))
    r = min(num(e.get("rx", 0)), w / 2, h / 2)
    if not r:
        return f"M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}Z", [(x, y), (x + w, y + h), (x + w, y), (x, y + h)]
    d = (f"M{x+r:g},{y:g}H{x+w-r:g}A{r:g},{r:g} 0,0 1 {x+w:g},{y+r:g}V{y+h-r:g}"
         f"A{r:g},{r:g} 0,0 1 {x+w-r:g},{y+h:g}H{x+r:g}A{r:g},{r:g} 0,0 1 {x:g},{y+h-r:g}"
         f"V{y+r:g}A{r:g},{r:g} 0,0 1 {x+r:g},{y:g}Z")
    return d, [(x, y), (x + w, y + h), (x + w, y), (x, y + h)]


def circle(e):
    cx, cy, r = (num(e.get(k)) for k in ("cx", "cy", "r"))
    d = f"M{cx-r:g},{cy:g}a{r:g},{r:g} 0,1 0 {2*r:g},0a{r:g},{r:g} 0,1 0 {-2*r:g},0Z"
    return d, [(cx - r, cy - r), (cx + r, cy + r), (cx + r, cy - r), (cx - r, cy + r)]


def polygon(e):
    pts = [tuple(map(float, p.split(","))) for p in e.get("points").split()]
    return "M" + "L".join(f"{x:g},{y:g}" for x, y in pts) + "Z", pts


def path(e):
    d = e.get("d")
    nums = list(map(float, re.findall(r"-?\d+(?:\.\d+)?", d)))
    return d, list(zip(nums[0::2], nums[1::2]))


def main(src):
    root = ET.parse(src).getroot()
    rot, pivot = 0.0, (256.0, 256.0)
    shapes = []
    for e in root.iter():
        tag = e.tag.replace(NS, "")
        if tag == "g" and e.get("transform", "").startswith("rotate"):
            a, px, py = map(float, re.findall(r"-?\d+(?:\.\d+)?", e.get("transform")))
            rot, pivot = a, (px, py)
        if tag in ("rect", "circle", "polygon", "path") and e.get("fill") not in (None, "none"):
            # El fondo (el rect de 512) va en el fondo del ícono adaptable, no acá.
            if tag == "rect" and e.get("width") == "512":
                continue
            d, pts = {"rect": rect, "circle": circle, "polygon": polygon, "path": path}[tag](e)
            shapes.append((d, color(e.get("fill")), pts))
    # Qué tan lejos del centro llega el dibujo (la rotación no cambia distancias al pivote).
    far = max(math.hypot(x - pivot[0], y - pivot[1]) for _, _, pts in shapes for x, y in pts)
    scale = (MAX_DP * 512 / 108) / far
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- El reloj de arena inclinado (docs/logo/slowtown.svg), que eligió Marcos el',
           '     23/10. Lo genera docs/logo/a_vector.py: no se edita a mano. Achicado para',
           f'     que nada pase de {MAX_DP:g} dp del centro, y los launchers no lo recorten. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="512"', '    android:viewportHeight="512">',
           f'    <group android:pivotX="256" android:pivotY="256" android:scaleX="{scale:.4f}" android:scaleY="{scale:.4f}">',
           f'        <group android:pivotX="{pivot[0]:g}" android:pivotY="{pivot[1]:g}" android:rotation="{rot:g}">']
    for d, fill, _ in shapes:
        out.append(f'            <path android:fillColor="{fill}" android:pathData="{d}" />')
    out += ['        </group>', '    </group>', '</vector>']
    print("\n".join(out))


if __name__ == "__main__":
    main(sys.argv[1])
