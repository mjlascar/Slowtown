"""Dibuja las propuestas de logo de Slowtown en SVG (propuestas-2/). Sin dependencias.

    python3 docs/logo/generar.py
"""
import math
import os

NAVY, NAVY2 = "#0B1020", "#1B2547"
CREAM, AMBER, AMBER2, WOOD = "#F3E7CF", "#FFB547", "#E8902A", "#C98A44"
DESTINO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "propuestas-2")


class Logo:
    """Un ícono de 512×512: el fondo, lo que va en <defs> y el dibujo."""

    def __init__(self, bg="#000", grad=False):
        self.defs, self.body, self.grad, self.bg = [], [], grad, bg
        if grad:
            self.defs.append(
                f'<linearGradient id="noche" x1="0" y1="0" x2="0" y2="1">'
                f'<stop offset="0" stop-color="{NAVY2}"/><stop offset="1" stop-color="#05070D"/></linearGradient>')

    def add(self, *parts):
        self.body.extend(parts)
        return self

    def crescent(self, x, y, r, color, dx=0.5, dy=-0.35):
        # Con máscara y no pintando encima: sobre un degradé, el círculo que recorta se vería.
        mid = f"luna{len(self.defs)}"
        self.defs.append(f'<mask id="{mid}"><rect width="512" height="512" fill="#fff"/>'
                         f'<circle cx="{x + r*dx:.1f}" cy="{y + r*dy:.1f}" r="{r*0.88:.1f}" fill="#000"/></mask>')
        return self.add(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{color}" mask="url(#{mid})"/>')

    def svg(self):
        fondo = 'url(#noche)' if self.grad else self.bg
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">'
                f'<defs>{"".join(self.defs)}</defs><rect width="512" height="512" fill="{fondo}"/>'
                f'{"".join(self.body)}</svg>\n')


def star(x, y, r, color):
    """Estrella de cuatro puntas, chiquita."""
    k = r * 0.28
    return (f'<path d="M{x},{y-r} L{x+k},{y-k} L{x+r},{y} L{x+k},{y+k} L{x},{y+r} '
            f'L{x-k},{y+k} L{x-r},{y} L{x-k},{y-k} Z" fill="{color}"/>')


def house(x0, x1, apex, wall, base, color):
    return f'<polygon points="{x0},{base} {x0},{wall} {(x0+x1)/2},{apex} {x1},{wall} {x1},{base}" fill="{color}"/>'


# ── El caracol ────────────────────────────────────────────────────────────────
FOOT = ("M112,348 C112,318 132,298 156,298 C176,298 188,312 194,330 L214,348 L362,348 "
        "C386,348 406,358 420,372 C406,377 386,378 362,378 L150,378 C128,378 112,366 112,348 Z")
CX, CY = 290, 238


def stalks(color, w=10):
    return (f'<g fill="none" stroke="{color}" stroke-width="{w}" stroke-linecap="round">'
            f'<path d="M142,302 Q130,270 120,246"/><path d="M164,300 Q168,270 174,248"/></g>'
            f'<circle cx="120" cy="244" r="10" fill="{color}"/><circle cx="174" cy="246" r="10" fill="{color}"/>')


def ticks(r_out, r_in, color, w, every=1):
    out = []
    for i in range(0, 12, every):
        a = math.radians(i * 30 - 90)
        big = i % 3 == 0
        ri = r_in - (6 if big else 0)
        out.append(f'<line x1="{CX + r_out*math.cos(a):.1f}" y1="{CY + r_out*math.sin(a):.1f}" '
                   f'x2="{CX + ri*math.cos(a):.1f}" y2="{CY + ri*math.sin(a):.1f}" '
                   f'stroke="{color}" stroke-width="{w + (3 if big else 0)}" stroke-linecap="round"/>')
    return "".join(out)


def hands(color, w, hour, minute):
    return (f'<g stroke="{color}" stroke-width="{w}" stroke-linecap="round">'
            f'<line x1="{CX}" y1="{CY}" x2="{CX}" y2="{CY - hour}"/>'
            f'<line x1="{CX}" y1="{CY}" x2="{CX + minute[0]}" y2="{CY + minute[1]}"/></g>'
            f'<circle cx="{CX}" cy="{CY}" r="{w*0.75:.0f}" fill="{color}"/>')


def caracol_bn():
    return Logo().add(
        f'<path d="{FOOT}" fill="#fff"/>', stalks("#fff"),
        f'<circle cx="{CX}" cy="{CY}" r="106" fill="#fff"/>',
        f'<circle cx="{CX}" cy="{CY}" r="88" fill="none" stroke="#000" stroke-width="5"/>',
        ticks(78, 68, "#000", 5, every=3), hands("#000", 17, 56, (44, -30)))


def caracol_espiral():
    pts, turns, r0, r1, n = [], 2.3, 14, 98, 220
    for i in range(n + 1):
        t = i / n
        a = -math.pi / 2 + t * turns * 2 * math.pi
        r = r0 + (r1 - r0) * t
        pts.append(f"{CX + r*math.cos(a):.1f},{CY + r*math.sin(a):.1f}")
    logo = Logo(bg=NAVY).add(
        f'<path d="{FOOT}" fill="{CREAM}"/>', stalks(CREAM),
        f'<path d="M{" L".join(pts)}" fill="none" stroke="{CREAM}" stroke-width="17" stroke-linecap="round" stroke-linejoin="round"/>',
        f'<line x1="{CX}" y1="{CY}" x2="{CX}" y2="{CY-46}" stroke="{AMBER}" stroke-width="12" stroke-linecap="round"/>',
        f'<circle cx="{CX}" cy="{CY}" r="11" fill="{AMBER}"/>')
    return logo.crescent(132, 132, 24, CREAM).add(star(196, 100, 9, CREAM), star(430, 150, 7, CREAM))


def caracol_ambar():
    logo = Logo(grad=True).add(
        f'<path d="{FOOT}" fill="{CREAM}"/>', stalks(CREAM),
        f'<circle cx="{CX}" cy="{CY}" r="106" fill="{AMBER}"/>',
        f'<circle cx="{CX}" cy="{CY}" r="101" fill="none" stroke="{AMBER2}" stroke-width="10"/>',
        f'<circle cx="{CX}" cy="{CY}" r="80" fill="{CREAM}"/>',
        ticks(72, 62, NAVY, 5), hands(NAVY, 15, 50, (40, -28)))
    return logo.crescent(132, 132, 24, CREAM).add(star(198, 104, 8, CREAM))


# ── La pausa ──────────────────────────────────────────────────────────────────
def pausa_bn():
    logo = Logo()
    for x0 in (152, 280):
        logo.add(house(x0, x0 + 80, 150, 210, 394, "#fff"),
                 f'<rect x="{x0+24}" y="246" width="32" height="30" rx="3" fill="#000"/>',
                 f'<line x1="{x0+40}" y1="246" x2="{x0+40}" y2="276" stroke="#fff" stroke-width="4"/>',
                 f'<path d="M{x0+26},394 L{x0+26},350 Q{x0+40},334 {x0+54},350 L{x0+54},394 Z" fill="#000"/>')
    logo.add('<polygon points="338,160 352,160 352,195 338,174" fill="#fff"/>')
    return logo.crescent(118, 128, 26, "#fff").add(star(410, 110, 11, "#fff"), star(186, 98, 7, "#fff"))


def pausa_luna():
    logo = Logo(bg=NAVY).add(f'<circle cx="256" cy="232" r="152" fill="{CREAM}"/>')
    for x0 in (166, 278):
        logo.add(house(x0, x0 + 68, 168, 222, 440, NAVY),
                 f'<rect x="{x0+22}" y="262" width="24" height="24" rx="2" fill="{AMBER}"/>')
    return logo.add(star(92, 96, 8, CREAM), star(430, 400, 7, CREAM), star(70, 330, 6, CREAM))


def pausa_ventanas():
    logo = Logo(grad=True)
    for x0 in (152, 280):
        logo.add(house(x0, x0 + 80, 150, 210, 394, CREAM))
        for y in (236, 296):
            logo.add(f'<rect x="{x0+24}" y="{y}" width="32" height="34" rx="3" fill="{AMBER}"/>')
        logo.add(f'<rect x="{x0+26}" y="352" width="28" height="42" rx="3" fill="{AMBER2}"/>')
    return logo.crescent(396, 112, 28, CREAM).add(star(120, 120, 10, CREAM), star(200, 80, 6, CREAM), star(440, 230, 6, CREAM))


# ── El reloj de arena ─────────────────────────────────────────────────────────
GLASS = ("M190,130 C190,200 248,222 248,256 C248,290 190,312 190,382 "
         "L322,382 C322,312 264,290 264,256 C264,222 322,200 322,130 Z")
TOP_SAND = "M224,208 Q256,220 288,208 L263,248 L249,248 Z"
MOUND = "M198,378 Q256,366 314,378 L314,382 L198,382 Z"


def town(color, window=None):
    t = house(204, 238, 324, 344, 378, color) + house(240, 276, 310, 332, 378, color) + house(278, 310, 328, 346, 378, color)
    if window:
        for x, y in ((215, 352), (252, 342), (288, 354)):
            t += f'<rect x="{x}" y="{y}" width="12" height="12" rx="1" fill="{window}"/>'
    return t


def frame(color, plate):
    return (f'<rect x="150" y="104" width="212" height="22" rx="9" fill="{plate}"/>'
            f'<rect x="150" y="386" width="212" height="22" rx="9" fill="{plate}"/>'
            f'<rect x="162" y="122" width="12" height="268" rx="4" fill="{color}"/>'
            f'<rect x="338" y="122" width="12" height="268" rx="4" fill="{color}"/>')


def reloj_bn():
    return Logo().add(
        frame("#fff", "#fff"),
        f'<path d="{GLASS}" fill="none" stroke="#fff" stroke-width="11" stroke-linejoin="round"/>',
        f'<path d="{TOP_SAND}" fill="#fff"/>',
        '<line x1="256" y1="250" x2="256" y2="312" stroke="#fff" stroke-width="5" stroke-linecap="round"/>',
        town("#fff", window="#000"), f'<path d="{MOUND}" fill="#fff"/>')


def reloj_noche():
    return Logo(grad=True).add(
        frame(WOOD, WOOD),
        f'<path d="{GLASS}" fill="{NAVY}" stroke="{CREAM}" stroke-width="8" stroke-linejoin="round"/>',
        f'<path d="{TOP_SAND}" fill="{AMBER}"/>',
        f'<line x1="256" y1="250" x2="256" y2="312" stroke="{AMBER}" stroke-width="5" stroke-linecap="round"/>',
        town(CREAM, window=AMBER), f'<path d="{MOUND}" fill="{AMBER}"/>',
        star(96, 160, 9, CREAM), star(420, 120, 7, CREAM), star(410, 300, 6, CREAM))


def reloj_geometrico():
    return Logo().add(
        '<rect x="150" y="100" width="212" height="24" rx="6" fill="#fff"/>',
        '<rect x="150" y="388" width="212" height="24" rx="6" fill="#fff"/>',
        '<polygon points="170,124 342,124 262,252 250,252" fill="#fff"/>',
        '<polygon points="250,260 262,260 342,388 170,388" fill="#fff"/>',
        '<polygon points="190,136 322,136 256,236" fill="#000"/>',
        f'<polygon points="224,188 288,188 256,236" fill="{AMBER}"/>',
        '<polygon points="256,276 324,376 188,376" fill="#000"/>',
        f'<line x1="256" y1="238" x2="256" y2="312" stroke="{AMBER}" stroke-width="6" stroke-linecap="round"/>',
        house(212, 240, 330, 346, 376, "#fff"), house(244, 270, 318, 336, 376, "#fff"),
        house(274, 300, 334, 350, 376, "#fff"))


LOGOS = {
    "1a-caracol": caracol_bn, "1b-caracol-espiral": caracol_espiral, "1c-caracol-ambar": caracol_ambar,
    "2a-pausa": pausa_bn, "2b-pausa-luna": pausa_luna, "2c-pausa-ventanas": pausa_ventanas,
    "3a-reloj": reloj_bn, "3b-reloj-noche": reloj_noche, "3c-reloj-geometrico": reloj_geometrico,
}

if __name__ == "__main__":
    os.makedirs(DESTINO, exist_ok=True)
    for nombre, dibujo in LOGOS.items():
        with open(os.path.join(DESTINO, f"{nombre}.svg"), "w") as f:
            f.write(dibujo().svg())
    print(len(LOGOS), "logos en", DESTINO)
