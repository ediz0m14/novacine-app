package app.novacine;

import android.content.Context;

/** Versión de la app (la pone GitHub Actions al compilar: 1.0.N). */
final class BuildConfigLite {
    private BuildConfigLite() { }

    static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "1.0"; }
    }
}
