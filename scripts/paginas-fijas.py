#!/usr/bin/env python3
"""
Arma la base de páginas del nivel 2: app/src/main/res/raw/paginas_fijas.txt.

Las listas UT1 de la Universidad Toulouse Capitole tienen millones de sitios
por categoría, y Chrome acepta hasta 1.000 en URLBlocklist. De cada categoría
van entonces las más visitadas, según el ranking Tranco (un millón de
dominios, promedio de cuatro rankings públicos de 30 días). Lo que queda
afuera, que es casi todo pero casi nada de lo que se visita, es para el DNS.

Cambiar esta lista es cambiar el nivel 2: una versión nueva de Slowtown,
instalada por cable.

Uso, con los archivos bajados a una carpeta vacía:

    https://dsi.ut-capitole.fr/blacklists/download/CATEGORIA.tar.gz
        (adult, gambling, games, audio-video, social_networks, dating)
    https://tranco-list.eu/top-1m.csv.zip
    https://tranco-list.eu/top-1m-id      (guardado como tranco-id.txt)

    python3 -I scripts/paginas-fijas.py CARPETA

Lee los archivos sin extraerlos a disco: son datos de afuera.
"""
import datetime
import pathlib
import re
import sys
import tarfile
import zipfile

REPO = pathlib.Path(__file__).resolve().parent.parent
SALIDA = REPO / "app/src/main/res/raw/paginas_fijas.txt"

# Cuántas de cada una, en este orden. Suman 830: con las de Fixed.CORE_SITES,
# los dominios enteros y lugar para las del nivel 1, queda debajo de 1.000.
CATEGORIAS = [
    ("adult", "eróticas", 330),
    ("gambling", "apuestas", 170),
    ("games", "juegos online", 150),
    ("audio-video", "video y streaming", 90),
    ("social_networks", "redes", 50),
    ("dating", "citas", 40),
]

# Una entrada tapa todo un dominio: los de adultos y de apuestas, y los
# dominios donde están las apuestas legales de Argentina y Brasil
# (bplay.bet.ar, codere.bet.ar). Van primero, y lo que ya tapan no ocupa lugar.
ENTEROS = ["xxx", "porn", "sex", "adult", "bet", "casino", "poker", "bingo", "bet.ar", "bet.br"]

# Nunca, aunque una lista las tenga.
NUNCA = {
    # Lo pidió Marcos: el ajedrez online queda.
    "chess.com", "lichess.org", "chess-results.com",
    # Ayuda para dejar el juego: justo lo que no se bloquea.
    "gamblersanonymous.org",
    # Música, podcasts y radio: el teléfono tiene que seguir sirviendo para eso.
    "spotify.com", "soundcloud.com", "deezer.com", "pandora.com", "tidal.com",
    "last.fm", "bandcamp.com", "mixcloud.com", "audiomack.com", "shazam.com",
    "ivoox.com", "podbean.com", "spreaker.com", "anchor.fm", "castbox.fm",
    "pocketcasts.com", "libsyn.com", "podomatic.com", "radiofrance.fr", "rfi.fr",
    "onlineradiobox.com", "radios.com.br", "allmusic.com", "winamp.com",
    "epidemicsound.com", "classiccat.net", "real.com", "boomplay.com",
    "boomplaymusic.com", "reverbnation.com", "mp3party.net", "napster.com",
    "live365.com", "hulkshare.com", "stereogum.com", "pri.org", "101.ru", "purevolume.com",
    "bcbits.com",
    # Chrome bloquea también los videos embebidos, y muchos cursos y páginas de
    # empresas ponen los suyos en Vimeo.
    "vimeo.com",
    # Para hablar, trabajar, pagar y estudiar.
    "whatsapp.com", "whatsapp.net", "telegram.org", "t.me", "signal.org",
    "messenger.com", "icq.com", "linkedin.com", "xing.com", "yammer.com",
    "researchgate.net", "academia.edu", "github.com", "skrill.com", "box.net",
    "starlink.com", "nokia.com", "obsproject.com", "logitechg.com", "razer.com",
    "razerzone.com", "gusto.com", "dan.com", "demon.co.uk", "bebee.com",
    "busuu.com", "italki.com", "byjus.com", "termwiki.com",
    # Diarios, guías, recetas y libros que las listas meten por un costado.
    "mundodeportivo.com", "mashable.com", "timeout.com", "eventbrite.com",
    "meetup.com", "theknot.com", "brides.com", "sheknows.com", "commondreams.org",
    "bfmtv.com", "francetvinfo.fr", "tf1info.fr", "ina.fr", "ghanaweb.com",
    "slashdot.org", "nationalgeographic.com", "emojipedia.org", "lego.com",
    "goodreads.com", "tripadvisor.com", "yelp.com", "allrecipes.com", "chowhound.com",
    "myheritage.com", "foursquare.com", "ravelry.com", "diigo.com",
    "buymeacoffee.com", "amplify.com", "helpshift.com", "linkvertise.com",
    "librarything.com", "geni.com", "delicious.com", "threadless.com",
    "couchsurfing.com", "care2.com", "mylife.com", "newsvine.com", "viadeo.com",
    # Empresas grandes con un poco de todo.
    "nvidia.com", "tencent.com", "netease.com", "microsoft.com", "apple.com",
    "amazon.com", "unity.com",
    # Dominios compartidos: bloquear uno tira abajo miles de sitios ajenos.
    "sa.com", "home.blog", "blogspot.com", "wordpress.com", "github.io",
    "netlify.app", "vercel.app", "pages.dev", "web.app", "firebaseapp.com",
    "appspot.com", "herokuapp.com", "wixsite.com", "weebly.com", "neocities.org",
}

# Servidores, publicidad y herramientas de otras páginas: nadie los abre como
# página, y URLBlocklist sólo frena páginas. Ocuparían lugar sin bloquear nada,
# y algunos (los botones de compartir, los comentarios) están en sitios ajenos.
TECNICOS = re.compile(r"cdn|static|server|services|^(api|img|media|video)\d*\.")
TECNICOS_SUELTOS = {
    "googlevideo.com", "nflxvideo.net", "nflxext.com", "netflix.net",
    "amazonvideo.com", "aiv-delivery.net", "tiktokv.com", "userapi.com",
    "hingeprod.net", "hingeaws.net", "hingenexus.com", "mtch.com",
    "playstation.net", "nintendo.net", "xboxlive.com", "pvp.net",
    "gravatar.com", "list-manage.com", "mailchimp.com", "applovin.com",
    "unity3d.com", "unity3dusercontent.com", "mediafire.com", "istockphoto.com",
    "highwebmedia.com", "adtng.com", "51.la", "sharethis.com", "addthis.com",
    "disqus.com", "vidazoo.com", "dmxleo.com", "brightcove.com", "smartclip.net",
    "steamcontent.com", "discordapp.net", "gameanalytics.com", "vivox.com",
    "gree-pf.net", "sng.link", "2kcoretech.online", "tnt-ea.com", "mediago.io",
    "cpmstar.com", "4dex.io", "epicgames.dev", "hdslb.com", "pinimg.com",
    "facebook.net", "viralize.tv", "playdigo.com", "2trk.info", "demonware.net",
    "juicer.io", "videoplaza.tv",
}

# Radio, podcasts y música, por el nombre: las listas de video los mezclan.
MUSICA = re.compile(r"radio|podcast|music|mp3|sound|audio|shout|muz")

def dominios(carpeta, categoria):
    with tarfile.open(carpeta / f"{categoria}.tar.gz", "r:gz") as tar:
        datos = tar.extractfile(f"{categoria}/domains").read()
    return {l.strip().lower() for l in datos.decode("utf-8", "replace").splitlines() if l.strip()}


def ranking(carpeta):
    puestos = {}
    with zipfile.ZipFile(carpeta / "tranco-top-1m.csv.zip") as z, z.open("top-1m.csv") as fh:
        for linea in fh:
            puesto, dominio = linea.decode().strip().split(",", 1)
            puestos[dominio] = int(puesto)
    return puestos


def cubierto(dominio, elegidos):
    """Si ya lo bloquea otro: Chrome bloquea también los subdominios."""
    partes = dominio.split(".")
    return any(".".join(partes[i:]) in elegidos for i in range(1, len(partes)))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    carpeta = pathlib.Path(sys.argv[1])
    puestos = ranking(carpeta)
    lista_id = (carpeta / "tranco-id.txt").read_text().strip() if (carpeta / "tranco-id.txt").exists() else "?"
    elegidos = set(ENTEROS)
    secciones = [("dominios enteros", None, list(ENTEROS))]
    for categoria, nombre, cupo in CATEGORIAS:
        todos = dominios(carpeta, categoria)
        candidatos = sorted((puestos[d], d) for d in todos if d in puestos)
        tomados = []
        for _, dominio in candidatos:
            if len(tomados) == cupo:
                break
            if (dominio in NUNCA or dominio in TECNICOS_SUELTOS or TECNICOS.search(dominio)
                    or MUSICA.search(dominio)
                    or dominio in elegidos or cubierto(dominio, elegidos | NUNCA)):
                continue
            tomados.append(dominio)
            elegidos.add(dominio)
        secciones.append((nombre, len(todos), tomados))

    hoy = datetime.date.today().isoformat()
    lineas = [
        "# El nivel 2 de Slowtown: páginas que Chrome no abre nunca, de la más",
        "# visitada a la menos dentro de cada categoría. No editar a mano: lo arma",
        f"# scripts/paginas-fijas.py ({hoy}). Cambiarla es una versión nueva, por cable.",
        "#",
        "# Fuente: listas UT1 de la Universidad Toulouse Capitole (Fabrice Prigent),",
        "# https://dsi.ut-capitole.fr/blacklists/, bajo licencia CC BY-SA 4.0. Orden:",
        f"# ranking Tranco, lista {lista_id} (https://tranco-list.eu/list/{lista_id}).",
        "# Este archivo se distribuye bajo la misma licencia, CC BY-SA 4.0.",
    ]
    for nombre, total, tomados in secciones:
        if total is None:
            lineas += ["", f"# {nombre}: cada uno tapa todos sus sitios"]
        else:
            lineas += ["", f"# {nombre}: las {len(tomados)} más visitadas de {total:,}".replace(",", ".")]
        lineas += tomados
    SALIDA.parent.mkdir(parents=True, exist_ok=True)
    SALIDA.write_text("\n".join(lineas) + "\n")
    for nombre, total, tomados in secciones:
        print(f"{nombre:18} {len(tomados):>4} de {total or len(tomados):>9}: {' '.join(tomados[:12])} …")
    print(f"total: {len(elegidos)} en {SALIDA.relative_to(REPO)}")


if __name__ == "__main__":
    main()
