# NovaCine para Android

App de Android que abre **novacine.app** en un WebView propio con escudo anti-publicidad.

## Qué hace
- Abre la web completa (se actualiza sola cuando cambia novacine.app).
- **Bloquea popups** y ventanas nuevas (salvo el inicio de sesión con Google).
- **Bloquea redirecciones**: ningún anuncio puede sacarte de NovaCine.
- **Corta los anuncios** de los dominios de `app/src/main/assets/bloqueo.txt`, también dentro de los reproductores.
  Al abrirse descarga además `https://novacine.app/app/bloqueo.txt` (se puede ampliar sin sacar otro APK; `!dominio` desbloquea).
- Telegram, redes y descargas se abren fuera solo si el usuario toca el enlace.
- Pantalla completa del video en horizontal, botón «atrás», subir imágenes, página «Sin conexión».
- **Android TV / Google TV**: aparece en el menú del televisor (banner `drawable-xhdpi/banner.png`), no exige pantalla táctil y añade `NovaCineTV` al user agent para que la web active su modo TV (control remoto). El botón Atrás pregunta primero a la web (`window.ncAtras()`).
- La web puede saber que está dentro de la app: el user agent incluye `NovaCineApp/1.0.N` y existe `window.NovaCineApp`.

## Compilar
No hace falta Android Studio: **GitHub Actions** compila en cada cambio en `main`
(o en Actions → «Compilar APK» → *Run workflow*) y publica `NovaCine.apk` en **Releases**.

### Firma (una sola vez) — Settings → Secrets and variables → Actions
| Secret | Valor |
|---|---|
| `NOVACINE_KEYSTORE_BASE64` | el archivo de firma en base64 |
| `NOVACINE_KEYSTORE_PASSWORD` | la contraseña |
| `NOVACINE_KEY_ALIAS` | `novacine` |
| `NOVACINE_KEY_PASSWORD` | la misma contraseña |

**Guarda una copia del archivo de firma y la contraseña.** Si se pierden, las versiones nuevas
no se podrán instalar encima de la anterior (habría que desinstalar).
Sin los secrets, Actions compila igual un APK de prueba (artefacto), pero no publica Release.
