"""Tercera tanda de logos (propuestas-3/): cinco caracoles y cinco relojes de arena.

Blanco y negro, con detalles en ámbar. El caparazón es parte del caracol (no
un círculo apoyado encima) y la espiral es el tiempo; el reloj de arena sale
del 3c, con menos regla y compás.

    python3 docs/logo/generar3.py
"""
import math
import os

from generar import AMBER, Logo, house

DESTINO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "propuestas-3")


def spiral(cx, cy, r_in, r_out, a_start, turns, n=260):
    """Puntos de una espiral de Arquímedes, de adentro hacia afuera. Ángulos en grados, sentido horario en pantalla."""
    pts = []
    for i in range(n + 1):
        t = i / n
        a = math.radians(a_start + t * turns * 360)
        r = r_in + (r_out - r_in) * t
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def poly(pts):
    return "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts)


# ── Caracoles ────────────────────────────────────────────────────────────────
# El pie sube y abraza el caparazón: la base del caparazón queda adentro del cuerpo.
BODY = ("M108,350 C108,320 128,300 152,300 C170,300 182,310 190,326 "
        "C200,344 216,352 240,352 L360,352 C392,352 414,362 430,378 "
        "C414,384 392,386 360,386 L146,386 C124,386 108,372 108,350 Z")
SX, SY, SR = 296, 238, 112


def stalks(color, eye=None):
    s = (f'<g fill="none" stroke="{color}" stroke-width="11" stroke-linecap="round">'
         f'<path d="M138,306 Q124,272 114,246"/><path d="M162,304 Q166,272 172,248"/></g>'
         f'<circle cx="114" cy="244" r="11" fill="{color}"/><circle cx="172" cy="246" r="11" fill="{color}"/>')
    if eye:
        s += f'<circle cx="114" cy="244" r="4" fill="{eye}"/><circle cx="172" cy="246" r="4" fill="{eye}"/>'
    return s


def shell_groove(width=13, turns=2.4, end=150):
    # La espiral termina abajo a la izquierda, donde el caparazón se une al cuerpo.
    a0 = end - turns * 360
    return (f'<path d="{poly(spiral(SX, SY, 10, SR - 18, a0, turns))}" fill="none" stroke="#000" '
            f'stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>')


def hand(length=40, w=12):
    return (f'<line x1="{SX}" y1="{SY}" x2="{SX}" y2="{SY - length}" stroke="{AMBER}" stroke-width="{w}" stroke-linecap="round"/>'
            f'<circle cx="{SX}" cy="{SY}" r="{w * 0.9:.0f}" fill="{AMBER}"/>')


def caracol_silueta():
    """Una sola silueta blanca: la espiral, en negativo; la aguja del tiempo, en ámbar."""
    return Logo().add(f'<path d="{BODY}" fill="#fff"/>', f'<circle cx="{SX}" cy="{SY}" r="{SR}" fill="#fff"/>',
                      stalks("#fff"), shell_groove(), hand(34, 11))


def caracol_una_linea():
    """Una sola línea: sale del centro, se desenrosca, baja por el cuello, rodea la cabeza y el pie, y vuelve al caparazón."""
    turns, end = 2.35, 150
    pts = spiral(SX, SY, 12, SR - 6, end - turns * 360, turns)
    rest = (" C188,300 172,302 158,302 C132,302 110,324 110,348 C110,372 126,386 150,386 "
            "L360,386 C392,386 420,384 438,378 C414,368 388,346 377,308")
    return Logo().add(
        f'<path d="{poly(pts)}{rest}" fill="none" stroke="#fff" stroke-width="16" stroke-linecap="round" stroke-linejoin="round"/>',
        '<g fill="none" stroke="#fff" stroke-width="12" stroke-linecap="round">'
        '<path d="M140,304 Q126,274 116,250"/><path d="M162,302 Q166,274 172,252"/></g>',
        '<circle cx="116" cy="248" r="10" fill="#fff"/><circle cx="172" cy="250" r="10" fill="#fff"/>',
        f'<circle cx="{SX}" cy="{SY}" r="11" fill="{AMBER}"/>')


def caracol_reloj_espiral():
    """La silueta con la espiral y doce marcas en el borde: un reloj que se enrosca."""
    marks = []
    for i in range(12):
        a = math.radians(i * 30 - 90)
        r1, r2 = SR - 4, SR - (16 if i % 3 == 0 else 10)
        marks.append(f'<line x1="{SX + r1*math.cos(a):.1f}" y1="{SY + r1*math.sin(a):.1f}" '
                     f'x2="{SX + r2*math.cos(a):.1f}" y2="{SY + r2*math.sin(a):.1f}" stroke="#000" '
                     f'stroke-width="{6 if i % 3 == 0 else 4}" stroke-linecap="round"/>')
    return Logo().add(f'<path d="{BODY}" fill="#fff"/>', f'<circle cx="{SX}" cy="{SY}" r="{SR}" fill="#fff"/>',
                      stalks("#fff"), "".join(marks), shell_groove(width=11, turns=2.0), hand(30, 10))


def caracol_arcos():
    """Gráfico y grueso: el caparazón son arcos que nacen del pie, y el centro es ámbar."""
    cx, base = 296, 352
    arcs = "".join(
        f'<path d="M{cx - r},{base} A{r},{r} 0 0 1 {cx + r},{base}" fill="none" stroke="#fff" stroke-width="24"/>'
        for r in (118, 80, 42))
    return Logo().add(
        '<path d="M108,352 C108,322 128,302 152,302 C176,302 192,322 196,352 Z" fill="#fff"/>',
        '<rect x="108" y="352" width="300" height="34" rx="17" fill="#fff"/>',
        '<path d="M380,352 C404,352 420,360 434,378 C420,384 404,386 380,386 Z" fill="#fff"/>',
        arcs, f'<circle cx="{cx}" cy="{base - 6}" r="15" fill="{AMBER}"/>', stalks("#fff"))


def caracol_grabado():
    """Más trabajado, como un grabado: líneas de crecimiento en el caparazón y textura en el pie."""
    growth = []
    for i in range(22):
        a = math.radians(150 - i * 16.5)
        r1, r2 = SR - 6, SR - 30 - (i % 2) * 8
        growth.append(f'<line x1="{SX + r1*math.cos(a):.1f}" y1="{SY + r1*math.sin(a):.1f}" '
                      f'x2="{SX + r2*math.cos(a):.1f}" y2="{SY + r2*math.sin(a):.1f}" stroke="#000" '
                      f'stroke-width="3" stroke-linecap="round"/>')
    foot = ('<g fill="none" stroke="#000" stroke-width="3" stroke-linecap="round">'
            '<path d="M200,372 L392,372"/><path d="M236,362 L360,362"/><path d="M122,340 Q130,318 150,312"/></g>')
    return Logo().add(f'<path d="{BODY}" fill="#fff"/>', f'<circle cx="{SX}" cy="{SY}" r="{SR}" fill="#fff"/>',
                      stalks("#fff", eye="#000"), "".join(growth), shell_groove(width=12, turns=2.4), foot, hand(32, 10))


def caracol_s():
    """La S de Slowtown hecha caracol: arriba se enrosca en el caparazón, abajo es el pie."""
    turns = 1.6
    pts = spiral(300, 210, 10, 92, 90 - turns * 360 - 180, turns)
    s_tail = (" C206,214 196,260 230,286 C262,310 352,300 380,330 C400,352 384,384 340,384 "
              "L160,384 C130,384 116,364 120,342")
    return Logo().add(
        f'<path d="{poly(pts)}{s_tail}" fill="none" stroke="#fff" stroke-width="30" stroke-linecap="round" stroke-linejoin="round"/>',
        '<g fill="none" stroke="#fff" stroke-width="11" stroke-linecap="round"><path d="M122,336 Q110,302 102,280"/>'
        '<path d="M140,330 Q146,300 152,280"/></g>',
        '<circle cx="102" cy="278" r="11" fill="#fff"/><circle cx="152" cy="278" r="11" fill="#fff"/>',
        f'<circle cx="300" cy="210" r="12" fill="{AMBER}"/>')


# ── Relojes de arena ─────────────────────────────────────────────────────────
# El vidrio con curvas, lleno de blanco, y adentro el negro: un borde parejo que no
# es de regla. La arena y el pueblo van adentro.
GLASS_OUT = ("M180,124 C180,198 236,228 246,256 C236,284 180,314 180,388 "
             "L332,388 C332,314 276,284 266,256 C276,228 332,198 332,124 Z")
GLASS_IN_TOP = "M198,138 C198,198 242,226 252,250 L260,250 C270,226 314,198 314,138 Z"
GLASS_IN_BOT = "M252,262 L260,262 C270,286 314,314 314,374 L198,374 C198,314 242,286 252,262 Z"
SAND_TOP = "M222,200 Q256,214 290,200 L262,247 L250,247 Z"


def plates(color="#fff"):
    return (f'<rect x="146" y="98" width="220" height="28" rx="12" fill="{color}"/>'
            f'<rect x="146" y="386" width="220" height="28" rx="12" fill="{color}"/>')


def grains(color=AMBER):
    return "".join(f'<circle cx="256" cy="{y}" r="{r}" fill="{color}"/>' for y, r in ((264, 5), (282, 4.5), (300, 4), (318, 3.5)))


def church(x, base, color, w=26):
    """Una iglesia: la nave y una torre con su aguja."""
    return (f'<rect x="{x}" y="{base-28}" width="{w}" height="28" fill="{color}"/>'
            f'<rect x="{x + w/2 - 7}" y="{base-52}" width="14" height="26" fill="{color}"/>'
            f'<polygon points="{x + w/2 - 9},{base-52} {x + w/2},{base-76} {x + w/2 + 9},{base-52}" fill="{color}"/>')


def pueblo(color, base=374, windows=None):
    t = (house(206, 234, 334, 350, base, color) + church(238, base, color) +
         house(270, 296, 336, 352, base, color))
    if windows:
        t += "".join(f'<rect x="{x}" y="{y}" width="9" height="9" fill="{windows}"/>' for x, y in ((215, 356), (247, 356), (278, 358)))
    return t


def reloj_organico(rot=0):
    g = (plates() + f'<path d="{GLASS_OUT}" fill="#fff"/>'
         f'<path d="{GLASS_IN_TOP}" fill="#000"/><path d="{GLASS_IN_BOT}" fill="#000"/>'
         f'<path d="{SAND_TOP}" fill="{AMBER}"/>' + grains() +
         f'<path d="M200,374 Q256,346 312,374 Z" fill="{AMBER}"/>' + pueblo("#fff", base=364, windows="#000"))
    if rot:
        g = f'<g transform="rotate({rot} 256 256)">{g}</g>'
    return Logo().add(g)


def reloj_torneado():
    """Marco de madera torneada, como los de antes: cada columna con sus cuentas."""
    def baluster(x):
        return (f'<rect x="{x-5}" y="126" width="10" height="260" fill="#fff"/>'
                + "".join(f'<ellipse cx="{x}" cy="{y}" rx="11" ry="9" fill="#fff"/>' for y in (150, 256, 362)))
    return Logo().add(
        '<rect x="140" y="92" width="232" height="16" rx="6" fill="#fff"/><rect x="152" y="106" width="208" height="20" rx="4" fill="#fff"/>',
        '<rect x="152" y="386" width="208" height="20" rx="4" fill="#fff"/><rect x="140" y="404" width="232" height="16" rx="6" fill="#fff"/>',
        baluster(170), baluster(342),
        f'<path d="{GLASS_OUT}" fill="none" stroke="#fff" stroke-width="9" stroke-linejoin="round" transform="translate(256 256) scale(0.92 1) translate(-256 -256)"/>',
        f'<path d="{SAND_TOP}" fill="{AMBER}"/>',
        f'<line x1="256" y1="248" x2="256" y2="322" stroke="{AMBER}" stroke-width="5" stroke-linecap="round"/>',
        f'<path d="M210,376 Q256,354 302,376 Z" fill="{AMBER}"/>', pueblo("#fff", base=366, windows=AMBER))


def reloj_a_mano():
    """Como dibujado a mano: bordes que no son del todo rectos y techos un poco torcidos."""
    out = ("M184,126 C178,196 238,224 244,254 C240,288 176,312 182,386 "
           "L330,388 C336,312 274,286 268,258 C280,226 334,200 330,122 Z")
    t_in = "M200,140 C196,198 244,224 252,250 L260,250 C268,226 316,198 312,138 Z"
    b_in = "M252,262 L260,262 C268,288 318,314 312,374 L198,372 C200,312 244,288 252,262 Z"
    houses = ('<polygon points="206,364 207,338 222,322 236,337 235,364" fill="#fff"/>'
              '<polygon points="240,364 241,330 255,306 270,329 268,364" fill="#fff"/>'
              '<polygon points="272,364 274,340 287,326 300,341 298,364" fill="#fff"/>')
    return Logo().add(
        '<path d="M148,104 Q256,92 364,102 L362,128 Q256,120 150,130 Z" fill="#fff"/>',
        '<path d="M150,384 Q256,392 362,386 L364,412 Q256,420 148,410 Z" fill="#fff"/>',
        f'<path d="{out}" fill="#fff"/><path d="{t_in}" fill="#000"/><path d="{b_in}" fill="#000"/>',
        f'<path d="M224,202 Q240,214 258,208 Q276,214 290,201 L262,247 L250,247 Z" fill="{AMBER}"/>', grains(),
        f'<path d="M202,374 Q232,350 256,356 Q284,348 310,374 Z" fill="{AMBER}"/>', houses)


def reloj_pueblo_de_arena():
    """El pueblo está hecho de la arena que cayó: casas ámbar en el vidrio blanco."""
    return Logo().add(
        plates(), f'<path d="{GLASS_OUT}" fill="#fff"/>',
        f'<path d="{GLASS_IN_TOP}" fill="#000"/><path d="{GLASS_IN_BOT}" fill="#000"/>',
        f'<path d="{SAND_TOP}" fill="{AMBER}"/>',
        f'<line x1="256" y1="248" x2="256" y2="318" stroke="{AMBER}" stroke-width="5" stroke-linecap="round"/>',
        f'<path d="M200,374 Q256,352 312,374 Z" fill="{AMBER}"/>', pueblo(AMBER, base=368, windows="#000"))


LOGOS = {
    "1-1-silueta": caracol_silueta, "1-2-una-linea": caracol_una_linea, "1-3-reloj-espiral": caracol_reloj_espiral,
    "1-4-arcos": caracol_arcos, "1-5-grabado": caracol_grabado,
    "3-1-organico": reloj_organico, "3-2-torneado": reloj_torneado, "3-3-a-mano": reloj_a_mano,
    "3-4-inclinado": lambda: reloj_organico(rot=-12), "3-5-pueblo-de-arena": reloj_pueblo_de_arena,
}

if __name__ == "__main__":
    os.makedirs(DESTINO, exist_ok=True)
    for nombre, dibujo in LOGOS.items():
        with open(os.path.join(DESTINO, f"{nombre}.svg"), "w") as f:
            f.write(dibujo().svg())
    print(len(LOGOS), "logos en", DESTINO)
