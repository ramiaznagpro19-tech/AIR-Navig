package app.airnavig;

/** Navigation privée AIR Navig : processus séparé, données effacées à la sortie. */
public class IncognitoActivity extends MainActivity {
    @Override
    protected boolean incognito() {
        return true;
    }
}
