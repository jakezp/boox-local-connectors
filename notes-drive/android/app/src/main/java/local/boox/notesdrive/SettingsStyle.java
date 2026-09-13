package local.boox.notesdrive;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Original e-ink settings styling, matched to the supported BOOX native screens. */
final class SettingsStyle {
    static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
    static GradientDrawable outline(View view, int fill) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill); shape.setCornerRadius(dp(view, 7));
        shape.setStroke(Math.max(1, dp(view, 1)), Color.BLACK);
        return shape;
    }
    static void screen(Activity activity, ScrollView scroll, LinearLayout body, String title) {
        activity.getWindow().setStatusBarColor(Color.WHITE);
        activity.getWindow().setNavigationBarColor(Color.WHITE);
        activity.getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        body.setPadding(dp(body,30), dp(body,12), dp(body,30), dp(body,36));
        body.setBackgroundColor(Color.WHITE);
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(activity); back.setText("←"); back.setTextSize(28);
        back.setTextColor(Color.BLACK); back.setGravity(Gravity.CENTER);
        back.setContentDescription("Back"); back.setOnClickListener(v -> activity.finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(body,48),dp(body,56)));
        TextView name = new TextView(activity); name.setText(title); name.setTextSize(21);
        name.setTextColor(Color.BLACK); header.addView(name);
        body.addView(header,0,new LinearLayout.LayoutParams(-1,dp(body,72)));
    }
    static void section(LinearLayout body, String title) {
        TextView text = new TextView(body.getContext()); text.setText(title); text.setTextSize(18);
        text.setTextColor(Color.BLACK); text.setPadding(dp(body,4),dp(body,24),0,dp(body,12));
        body.addView(text,new LinearLayout.LayoutParams(-1,-2));
    }
    static void text(TextView view, int size) {
        view.setTextColor(Color.BLACK); view.setTextSize(size);
        view.setTypeface(Typeface.DEFAULT); view.setLineSpacing(dp(view,2),1);
        view.setPadding(dp(view,4),dp(view,8),dp(view,4),dp(view,8));
    }
    static void control(View view) {
        view.setBackground(outline(view,Color.WHITE));
        view.setPadding(dp(view,16),dp(view,12),dp(view,16),dp(view,12));
        view.setMinimumHeight(dp(view,72));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.topMargin=dp(view,8); params.bottomMargin=dp(view,4); view.setLayoutParams(params);
        if(view instanceof TextView) {
            TextView text=(TextView)view;
            text.setTextSize(20); text.setTypeface(Typeface.DEFAULT); text.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
                new int[]{Color.GRAY,Color.BLACK}));
        }
        if(view instanceof Button) {
            Button button=(Button)view; button.setAllCaps(false); button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            button.setStateListAnimator(null);
        }
        if(view instanceof Switch) {
            Switch toggle=(Switch)view; toggle.setShowText(false); toggle.setSplitTrack(false);
            toggle.setSwitchMinWidth(dp(view,52)); toggle.setThumbDrawable(null);
            toggle.setTrackDrawable(new android.graphics.drawable.Drawable() {
                final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                public int getIntrinsicWidth(){return dp(view,52);}
                public int getIntrinsicHeight(){return dp(view,26);}
                public boolean isStateful(){return true;}
                protected boolean onStateChange(int[] state){invalidateSelf();return true;}
                public void draw(android.graphics.Canvas canvas) {
                    android.graphics.Rect b=getBounds();float h=dp(view,24),w=dp(view,50),x=b.centerX()-w/2,y=b.centerY()-h/2;
                    boolean on=false;for(int state:getState())on|=state==android.R.attr.state_checked;
                    android.graphics.RectF rect=new android.graphics.RectF(x,y,x+w,y+h);
                    paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(on?Color.BLACK:Color.WHITE);canvas.drawRoundRect(rect,dp(view,2),dp(view,2),paint);
                    paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(view,2));paint.setColor(Color.BLACK);canvas.drawRoundRect(rect,dp(view,2),dp(view,2),paint);
                    paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(on?Color.WHITE:Color.BLACK);
                    float box=dp(view,13),bx=on?x+w-dp(view,5)-box:x+dp(view,5);
                    canvas.drawRect(bx,y+(h-box)/2,bx+box,y+(h+box)/2,paint);
                    paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(dp(view,12));
                    canvas.drawText(on?"ON":"OFF",on?x+dp(view,5):x+dp(view,21),y+h/2-(paint.ascent()+paint.descent())/2,paint);
                }
                public void setAlpha(int alpha){paint.setAlpha(alpha);}
                public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
                public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
            });
        }
    }
}
