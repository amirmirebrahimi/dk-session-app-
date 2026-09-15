package ir.khatman.dksession;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Entry screen: collects the 12-digit access code, validates it, and hands it
 * off to {@link WebViewActivity} which fetches the session and opens Digikala.
 */
public class MainActivity extends AppCompatActivity {

    private static final int CODE_LENGTH = 12;

    // Status text colors.
    private static final int STATE_NORMAL = 0;
    private static final int STATE_SUCCESS = 1;
    private static final int STATE_ERROR = 2;

    private EditText codeInput;
    private TextView statusView;
    private SessionStore sessionStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sessionStore = new SessionStore(this);

        codeInput = findViewById(R.id.codeInput);
        statusView = findViewById(R.id.statusView);
        Button applyBtn = findViewById(R.id.applyBtn);

        // Prefill the last code the user entered, for convenience.
        String last = sessionStore.getLastCode();
        if (last != null) {
            codeInput.setText(last);
            codeInput.setSelection(last.length());
        }

        applyBtn.setOnClickListener(v -> submit());
        codeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                submit();
                return true;
            }
            return false;
        });
    }

    private void submit() {
        String code = codeInput.getText().toString().trim();

        if (!code.matches("\\d{" + CODE_LENGTH + "}")) {
            showStatus(getString(R.string.error_code_length), STATE_ERROR);
            return;
        }

        sessionStore.setLastCode(code);
        showStatus(getString(R.string.loading), STATE_NORMAL);

        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra(WebViewActivity.EXTRA_CODE, code);
        startActivity(intent);
    }

    private void showStatus(String message, int state) {
        statusView.setText(message);
        switch (state) {
            case STATE_SUCCESS:
                statusView.setTextColor(0xFF22C55E);
                break;
            case STATE_ERROR:
                statusView.setTextColor(0xFFEF4444);
                break;
            default:
                statusView.setTextColor(0xFFA1A1AA);
                break;
        }
        statusView.setVisibility(View.VISIBLE);
    }
}