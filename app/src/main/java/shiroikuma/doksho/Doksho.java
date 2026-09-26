package shiroikuma.doksho;

/**
 * shiroikuma-doksho (白い熊 読書): the fork's own identity, in one place. Upstream code that
 * pointed at Librera's website, FAQ, Telegram, e-mail or store listing points here instead.
 */
public final class Doksho {

    public static final String NAME = "白い熊 読書";
    public static final String GITHUB = "https://github.com/ShiroiKuma0/shiroikuma-doksho";
    public static final String RELEASES = GITHUB + "/releases";
    public static final String ISSUES = GITHUB + "/issues";
    public static final String HELP = GITHUB + "#readme";
    /** The repo / APK basename — and the prefix of every backup: shiroikuma-doksho_<stamp>.zip. */
    public static final String EXPORT_SLUG = "shiroikuma-doksho";

    private Doksho() {
    }
}
