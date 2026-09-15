package ir.khatman.dksession;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small SharedPreferences wrapper that remembers the last used access code and
 * the most recently fetched session payload. This is the Android equivalent of
 * the extension's chrome.storage usage.
 */
public class SessionStore {

    private static final String PREFS = "dk_session";
    private static final String KEY_LAST_CODE = "last_code";
    private static final String KEY_SESSION = "session_json";

    private final SharedPreferences prefs;

    public SessionStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getLastCode() {
        return prefs.getString(KEY_LAST_CODE, null);
    }

    public void setLastCode(String code) {
        prefs.edit().putString(KEY_LAST_CODE, code).apply();
    }

    public String getSessionJson() {
        return prefs.getString(KEY_SESSION, null);
    }

    public void saveSessionJson(String json) {
        prefs.edit().putString(KEY_SESSION, json).apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
