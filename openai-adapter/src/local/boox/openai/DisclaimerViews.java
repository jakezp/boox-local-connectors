package local.boox.openai;

import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Hides static BOOX disclaimers while preserving the space below the input. */
final class DisclaimerViews {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final Map<View, Integer> visibility = Collections.synchronizedMap(
        new WeakHashMap<View, Integer>());

    static void install() {
        XposedBridge.hookAllConstructors(TextView.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length < 2 || !(param.args[1] instanceof AttributeSet)) return;
                AttributeSet attributes = (AttributeSet) param.args[1];
                int textResource = attributes.getAttributeResourceValue(ANDROID_NS, "text", 0);
                if (textResource == 0) return;
                TextView view = (TextView) param.thisObject;
                try {
                    String name = view.getResources().getResourceEntryName(textResource);
                    int value;
                    if ("assistant_declaration_tips".equals(name)) value = View.INVISIBLE;
                    else if ("conversation_reply_tips".equals(name)) value = View.GONE;
                    else return;
                    visibility.put(view, value);
                    view.setVisibility(value);
                } catch (Resources.NotFoundException ignored) {}
            }
        });
        XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Integer value = visibility.get(param.thisObject);
                if (value != null) param.args[0] = value;
            }
        });
    }
}
