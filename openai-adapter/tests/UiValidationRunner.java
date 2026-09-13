package local.boox.openai;

import android.app.Instrumentation;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** Exercises native UI error rendering without sending an API request. */
public final class UiValidationRunner extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            AccessibilityNodeInfo input = find("et_question");
            Bundle text = new Bundle();
            text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                new String(new char[60001]).replace('\0', 'x'));
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
                throw new AssertionError("Could not enter synthetic oversized prompt");
            Thread.sleep(250);
            if (!find("btn_send").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                throw new AssertionError("Could not send synthetic oversized prompt");
            String expected = "The selected text is too long. Select a shorter passage.";
            boolean found = false;
            for (int i = 0; i < 50; i++) {
                Thread.sleep(100);
                AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                if (root != null && !root.findAccessibilityNodeInfosByText(expected).isEmpty()) { found = true; break; }
            }
            if (!found) throw new AssertionError("Sanitized error missing from native UI");
            find("et_question");
            result.putString("stream", "\nPASS native oversized-input error displays the actionable message and restores input\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL native error UI: " + error.getClass().getSimpleName() + "\n");
            finish(0, result);
        }
    }
    private AccessibilityNodeInfo find(String name) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) throw new AssertionError("No active native UI");
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId("com.onyx.aiassistant:id/" + name);
        if (nodes.size() != 1) throw new AssertionError("Expected one native view: " + name);
        return nodes.get(0);
    }
}
