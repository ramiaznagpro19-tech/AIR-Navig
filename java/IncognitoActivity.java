package app.airnavig;

/** Navigation privée AIR Navig : session privée GeckoView, rien n'est gardé à la fermeture. */
public class IncognitoActivity extends MainActivity {
    @Override
    protected boolean incognito() {
        return true;
    }
}
