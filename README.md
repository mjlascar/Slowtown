# Slowtown

Un launcher para Android que convierte el teléfono de todos los días en un
dumbphone que sigue siendo útil: llamadas, mensajes, WhatsApp, banco, mapas,
música y búsqueda. Lo demás queda cerrado, y aflojarlo cuesta a propósito.

Está hecho para un Galaxy S10e con Android 12, en Kotlin y Jetpack Compose.

## Qué hace

- **Inicio en texto:** la hora, el tiempo de pantalla del día, las apps que
  elegiste y tus hábitos. Deslizando para arriba, todas las apps, con carpetas
  y buscador.
- **Slowtown activo:** con Device Owner y lock task sólo se abren las apps
  permitidas. No hay Recientes ni ajustes rápidos, e Inicio es siempre
  Slowtown. La configuración pide una contraseña larga.
- **Dos niveles:** el primero es lo que se cambia con la contraseña (las apps
  permitidas y las páginas que se suman). El segundo sólo cambia con una
  versión nueva, instalada por cable: las políticas de Chrome, con la lista de
  bloqueo por defecto de la Universidad Toulouse Capitole, y las apps que no se
  abren nunca.
- **Bedtime:** de noche sólo se abren el reloj y las alarmas (y las
  llamadas, SMS y emergencias). Para usar el teléfono, ráfagas de 5 minutos.
  Nunca corta una llamada, tampoco las de WhatsApp.
- **Notificaciones por oleadas:** las de las apps que elijas esperan y llegan
  todas juntas, en silencio, a ciertas horas, salvo las que traen una palabra
  de «pasan siempre». Con «sólo los grupos», de WhatsApp esperan los grupos y
  los chats de a dos llegan en el momento.
- **«Habilitar todo por 5 minutos»:** sin contraseña, hasta 2 veces cada 12
  horas, para actualizar apps.
- **Hábitos como los cuadraditos de GitHub:** hasta 4 de un catálogo de 14,
  cada uno con su dibujo y sus veces por semana. Se marcan desde Inicio, y un
  fuego cuenta las semanas seguidas con la meta cumplida.
- **Siempre hay una salida por cable:** `scripts/liberar.sh`.

## Capturas

Del emulador (Android 12, sin cuentas), por eso las apps están en inglés.

| | | |
|:---:|:---:|:---:|
| <img src="docs/capturas/01-inicio.png" width="240"><br>Inicio | <img src="docs/capturas/02-habitos.png" width="240"><br>El resumen de hábitos | <img src="docs/capturas/03-elegir-habitos.png" width="240"><br>Elegir hábitos y sus veces por semana |
| <img src="docs/capturas/04-inicio-mas-habitos.png" width="240"><br>Inicio con más hábitos | <img src="docs/capturas/05-todas-las-apps.png" width="240"><br>Todas las apps | <img src="docs/capturas/06-contrasena.png" width="240"><br>La contraseña y «Habilitar todo» |
| <img src="docs/capturas/07-administracion.png" width="240"><br>La administración | <img src="docs/capturas/08-paginas-bloqueadas.png" width="240"><br>Páginas bloqueadas | <img src="docs/capturas/09-widget.png" width="240"><br>El widget de Inicio |
| <img src="docs/capturas/10-bedtime.png" width="240"><br>Bedtime | <img src="docs/capturas/11-de-noche.png" width="240"><br>De noche: sólo el reloj | <img src="docs/capturas/12-rafaga.png" width="240"><br>Una ráfaga de 5 minutos |
| <img src="docs/capturas/13-notificaciones.png" width="240"><br>Notificaciones: sólo los grupos | | |

## Cómo se prueba

`./scripts/probar-emulador.sh --limpio` recorre todo en un emulador con
Android 12: instalar, activar, intentos de escape, llamada, SMS, los dos
niveles, hábitos, tiempo de pantalla, reinicio, cuelgues y la salida por
cable. Termina con código 1 si algo falla.

Cómo se arma, las reglas que no se rompen y por qué: [`CLAUDE.md`](CLAUDE.md).
Lo que se investigó antes de escribir código: [`docs/investigacion.md`](docs/investigacion.md).
