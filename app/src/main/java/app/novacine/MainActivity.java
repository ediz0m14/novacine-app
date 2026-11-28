package app.novacine;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.Toast;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * NovaCine para Android: abre novacine.app en un WebView propio con «escudo» anti-publicidad.
 *  - Bloquea ventanas emergentes (popups) salvo el inicio de sesión de Google.
 *  - Impide que un anuncio saque al usuario de NovaCine (redirecciones).
 *  - Corta las peticiones a dominios de publicidad, también dentro de los reproductores.
 *  - Abre fuera (Telegram, redes, descargas) solo los enlaces que el usuario quiere abrir.
 */
public class MainActivity extends Activity {

    static final String INICIO = "https://novacine.app/";

    /** Páginas que se abren dentro de la app (la propia web). */
    static final String[] PROPIOS = { "novacine.app", "novacine.pages.dev" };
    /** Ventanas permitidas: solo el inicio de sesión con Google / Firebase. */
    static final String[] LOGIN = { "accounts.google.com", "firebaseapp.com", "apis.google.com", "accounts.youtube.com", "novacine.app" };
    /** Sitios que se abren en su app o en el navegador cuando el usuario toca un enlace. */
    static final String[] FUERA = { "t.me", "telegram.me", "telegram.org", "wa.me", "whatsapp.com", "facebook.com", "fb.me", "fb.com",
            "instagram.com", "tiktok.com", "youtube.com", "youtu.be", "x.com", "twitter.com", "twitch.tv", "kick.com", "discord.gg",
            "discord.com", "mega.nz", "mediafire.com", "drive.google.com", "pixeldrain.com", "1fichier.com", "terabox.com",
            "gofile.io", "play.google.com", "themoviedb.org", "anilist.co" };

    private WebView web;
    private FrameLayout raiz;
    private View vistaCompleta;                 // reproductor en pantalla completa
    private WebChromeClient.CustomViewCallback alSalirCompleta;
    private ValueCallback<Uri[]> archivos;      // para subir imágenes (avatar, etc.)
    private final Set<String> bloqueados = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> permitidos = Collections.synchronizedSet(new HashSet<>());
    private boolean sinConexion = false;

    // ───────────────────────── Arranque ─────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle guardado) {
        super.onCreate(guardado);
        cargarLista(leer(getAssets(), "bloqueo.txt"));
        File copia = new File(getFilesDir(), "bloqueo-remoto.txt");
        if (copia.exists()) cargarLista(leerArchivo(copia));
        actualizarListaRemota(copia);

        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.parseColor("#090D16"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#090D16"));
        raiz.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(raiz);

        configurar(web);
        web.setWebViewClient(new Cliente());
        web.setWebChromeClient(new Cromo());
        web.addJavascriptInterface(new Puente(), "NovaCineApp");
        web.setDownloadListener((url, ua, cd, mime, largo) -> abrirFuera(Uri.parse(url)));

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        if (guardado != null) web.restoreState(guardado);
        else web.loadUrl(destinoDe(getIntent()));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configurar(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);   // ningún anuncio abre ventanas solo
        s.setSupportMultipleWindows(true);                   // las ventanas pasan por onCreateWindow (y ahí se filtran)
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        // Google no deja iniciar sesión desde un WebView marcado como tal («; wv»).
        // Además se añade «NovaCineApp» para que la web sepa que está dentro de la app.
        String ua = s.getUserAgentString().replace("; wv", "");
        // En un televisor la web entra en «modo TV» (control remoto): ver components/ModoTV.jsx en la web
        s.setUserAgentString(ua + " NovaCineApp/" + BuildConfigLite.version(this) + (esTV() ? " NovaCineTV" : ""));
    }

    private String destinoDe(Intent i) {
        Uri u = i != null ? i.getData() : null;
        return u != null && esDe(u.getHost(), PROPIOS) ? u.toString() : INICIO;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getData() != null) web.loadUrl(destinoDe(i));
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        web.saveState(b);
    }

    @Override
    protected void onPause() { super.onPause(); web.onPause(); CookieManager.getInstance().flush(); }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    public void onBackPressed() {
        if (vistaCompleta != null) { cerrarPantallaCompleta(); return; }
        // Primero la web: cierra una ventana abierta o saca el foco del reproductor (window.ncAtras)
        web.evaluateJavascript("(function(){try{return !!(window.ncAtras&&window.ncAtras())}catch(e){return false}})()", r -> {
            if ("true".equals(r)) return;
            if (web.canGoBack()) web.goBack();
            else MainActivity.super.onBackPressed();
        });
    }

    /** ¿Es un televisor (Android TV / Google TV)? */
    private boolean esTV() {
        android.app.UiModeManager ui = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        return (ui != null && ui.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
            || getPackageManager().hasSystemFeature("android.software.leanback");
    }

    // ───────────────────────── Lista de bloqueo ─────────────────────────

    private void cargarLista(String texto) {
        if (texto == null) return;
        for (String linea : texto.split("\n")) {
            String d = linea.trim().toLowerCase();
            if (d.isEmpty() || d.startsWith("#")) continue;
            if (d.startsWith("!")) { permitidos.add(d.substring(1)); bloqueados.remove(d.substring(1)); }
            else if (!permitidos.contains(d)) bloqueados.add(d);
        }
    }

    /** Descarga la lista de la web (novacine.app/app/bloqueo.txt): se puede ampliar sin sacar otro APK. */
    private void actualizarListaRemota(File copia) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(INICIO + "app/bloqueo.txt").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                String t = leerFlujo(c.getInputStream());
                if (t == null || t.length() > 200_000) return;
                cargarLista(t);
                try (FileOutputStream o = new FileOutputStream(copia)) { o.write(t.getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception ignorado) { }
        }).start();
    }

    private boolean bloqueado(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        while (true) {
            if (permitidos.contains(h)) return false;
            if (bloqueados.contains(h)) return true;
            int p = h.indexOf('.');
            if (p < 0 || h.indexOf('.', p + 1) < 0) return false;
            h = h.substring(p + 1);
        }
    }

    static boolean esDe(String host, String[] lista) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String d : lista) if (h.equals(d) || h.endsWith("." + d)) return true;
        return false;
    }

    // ───────────────────────── Navegación ─────────────────────────

    private class Cliente extends WebViewClient {
        private final WebResourceResponse vacio() {
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            // Corta anuncios en toda la página, incluidos los reproductores (iframes)
            return bloqueado(r.getUrl().getHost()) ? vacio() : null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!r.isForMainFrame()) return bloqueado(u.getHost()); // dentro de un reproductor: solo se corta si es publicidad
            String esquema = u.getScheme() == null ? "" : u.getScheme();
            if (esquema.equals("intent") || esquema.equals("tg") || esquema.equals("whatsapp") || esquema.equals("mailto") || esquema.equals("tel")) {
                if (r.hasGesture()) abrirFuera(u);
                return true;
            }
            if (esDe(u.getHost(), PROPIOS) || esDe(u.getHost(), LOGIN)) return false; // NovaCine e inicio de sesión: dentro
            if (r.hasGesture() && esDe(u.getHost(), FUERA)) abrirFuera(u);           // el usuario tocó un enlace a Telegram, redes…
            return true;                                                              // cualquier otra cosa (redirección de anuncio): bloqueada
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            sinConexion = false;
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame() || sinConexion) return;
            sinConexion = true;
            v.loadDataWithBaseURL(INICIO, PAGINA_SIN_CONEXION, "text/html", "utf-8", null);
        }
    }

    static final String PAGINA_SIN_CONEXION = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{margin:0;height:100vh;display:grid;place-items:center;background:#090D16;color:#e2e8f0;font-family:sans-serif;text-align:center}"
            + "b{color:#FF3F4F;font-size:42px}button{margin-top:18px;background:#FF3F4F;color:#fff;border:0;border-radius:12px;padding:12px 22px;font-size:16px;font-weight:700}</style></head>"
            + "<body><div><b>N</b><h2>Sin conexión</h2><p>Revisa tu internet y vuelve a intentarlo.</p>"
            + "<button onclick=\"location.href='" + INICIO + "'\">Reintentar</button></div></body></html>";

    // ───────────────────────── Ventanas, pantalla completa, archivos ─────────────────────────

    private class Cromo extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView v, boolean dialogo, boolean gesto, Message resultado) {
            if (!gesto) return false; // ventanas sin que el usuario toque nada: siempre bloqueadas
            WebView ventana = new WebView(MainActivity.this);
            configurar(ventana);
            ventana.setWebViewClient(new FiltroVentana(ventana));
            ventana.setWebChromeClient(new WebChromeClient() {
                @Override public void onCloseWindow(WebView w) { FiltroVentana.cerrar(w); }
            });
            CookieManager.getInstance().setAcceptThirdPartyCookies(ventana, true);
            ((WebView.WebViewTransport) resultado.obj).setWebView(ventana);
            resultado.sendToTarget();
            return true;
        }

        @Override
        public void onShowCustomView(View vista, CustomViewCallback alSalir) {
            if (vistaCompleta != null) { alSalir.onCustomViewHidden(); return; }
            vistaCompleta = vista;
            alSalirCompleta = alSalir;
            raiz.addView(vista, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            barras(false);
        }

        @Override
        public void onHideCustomView() { cerrarPantallaCompleta(); }

        @Override
        public void onPermissionRequest(PermissionRequest p) { p.deny(); } // ni cámara ni micrófono

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (archivos != null) archivos.onReceiveValue(null);
            archivos = cb;
            try { startActivityForResult(params.createIntent(), 77); }
            catch (ActivityNotFoundException e) { archivos = null; return false; }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int pedido, int res, Intent datos) {
        super.onActivityResult(pedido, res, datos);
        if (pedido == 77 && archivos != null) {
            archivos.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, datos));
            archivos = null;
        }
    }

    private void cerrarPantallaCompleta() {
        if (vistaCompleta == null) return;
        raiz.removeView(vistaCompleta);
        vistaCompleta = null;
        web.setVisibility(View.VISIBLE);
        if (alSalirCompleta != null) alSalirCompleta.onCustomViewHidden();
        alSalirCompleta = null;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        barras(true);
    }

    @SuppressWarnings("deprecation")
    private void barras(boolean mostrar) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (mostrar) c.show(WindowInsets.Type.systemBars());
            else { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        } else {
            w.getDecorView().setSystemUiVisibility(mostrar ? View.SYSTEM_UI_FLAG_VISIBLE
                    : View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /**
     * Decide qué hacer con una ventana nueva apenas se sabe a dónde va:
     * inicio de sesión → se muestra encima · Telegram/redes/descargas → se abre fuera · lo demás (anuncios) → se cierra.
     */
    private class FiltroVentana extends WebViewClient {
        private final WebView ventana;
        private Dialog dialogo;
        private boolean decidido = false;

        FiltroVentana(WebView v) { ventana = v; }

        static void cerrar(WebView w) {
            Object d = w.getTag();
            if (d instanceof Dialog) ((Dialog) d).dismiss();
            w.destroy();
        }

        private boolean decidir(Uri u) {
            if (u == null || "about".equals(u.getScheme())) return false;
            String host = u.getHost();
            if (decidido) return bloqueado(host);
            decidido = true;
            if (esDe(host, LOGIN) && !bloqueado(host)) {
                dialogo = new Dialog(MainActivity.this, android.R.style.Theme_Material_NoActionBar);
                dialogo.setContentView(ventana);
                dialogo.setOnDismissListener(d -> ventana.destroy());
                ventana.setTag(dialogo);
                dialogo.show();
                return false;
            }
            if (esDe(host, FUERA) || esDe(host, PROPIOS)) abrirFuera(u);
            ventana.post(ventana::destroy);
            return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return decidir(r.getUrl()); }

        @Override
        public void onPageStarted(WebView v, String url, android.graphics.Bitmap icono) {
            if (!decidido && decidir(Uri.parse(url))) v.stopLoading();
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            return bloqueado(r.getUrl().getHost()) ? new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0])) : null;
        }
    }

    private void abrirFuera(Uri u) {
        runOnUiThread(() -> {
            try {
                Intent i = "intent".equals(u.getScheme()) ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, u);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
                startActivity(i);
            } catch (Exception ignorado) { }
        });
    }

    /** Lo que la web puede pedirle a la app: window.NovaCineApp.abrirFuera(url), .version() y .guardarImagen(dataUrl, nombre) */
    private class Puente {
        @JavascriptInterface
        public void abrirFuera(String url) {
            Uri u = Uri.parse(url);
            if ("https".equals(u.getScheme()) || "http".equals(u.getScheme()) || "tg".equals(u.getScheme())) MainActivity.this.abrirFuera(u);
        }

        @JavascriptInterface
        public String version() { return BuildConfigLite.version(MainActivity.this); }

        /** Guarda en la galería (Imágenes/NovaCine) una imagen que la web hizo: data:image/png;base64,… · devuelve true si se guardó */
        @JavascriptInterface
        public boolean guardarImagen(String dataUrl, String nombre) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || dataUrl == null) return false; // Android 9 o menos: la web muestra la imagen para guardarla a mano
            try {
                int coma = dataUrl.indexOf(',');
                byte[] bytes = Base64.decode(dataUrl.substring(coma + 1), Base64.DEFAULT);
                String limpio = (nombre == null || nombre.isEmpty() ? "novacine" : nombre).replaceAll("[^A-Za-z0-9._-]", "-");
                if (!limpio.endsWith(".png")) limpio += ".png";
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, limpio);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NovaCine");
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) return false;
                try (java.io.OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Imagen guardada en la galería (NovaCine)", Toast.LENGTH_SHORT).show());
                return true;
            } catch (Exception e) { return false; }
        }
    }

    // ───────────────────────── Utilidades ─────────────────────────

    static String leer(android.content.res.AssetManager a, String nombre) {
        try (InputStream i = a.open(nombre)) { return leerFlujo(i); } catch (Exception e) { return null; }
    }

    static String leerArchivo(File f) {
        try (InputStream i = new java.io.FileInputStream(f)) { return leerFlujo(i); } catch (Exception e) { return null; }
    }

    static String leerFlujo(InputStream i) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(i, StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
