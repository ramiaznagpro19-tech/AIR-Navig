package app.airnavig;

/** Navigation privée AIR Navig : session privée AIRCore, rien n'est gardé à la fermeture. */
public class IncognitoActivity extends MainActivity {
    @Override
    protected boolean incognito() {
        return true;
    }
}
