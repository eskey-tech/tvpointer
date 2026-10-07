package tech.eskey.tvpointer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Full-screen, non-touchable overlay that draws the arrow pointer; the tip is the hotspot. */
final class PointerView extends View {
    private final Path path = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float px, py;
    private boolean shown, pressed;

    PointerView(Context context) {
        super(context);
        fill.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setColor(Color.BLACK);
        setScale(Prefs.SIZE_FACTORS[1]);
    }

    /** @param unitDp size of one cell of the 12x19 arrow grid, in dp */
    void setScale(float unitDp) {
        float d = getResources().getDisplayMetrics().density;
        float u = unitDp * d;
        path.reset();
        path.moveTo(0, 0);
        path.lineTo(0, 17 * u);
        path.lineTo(4.2f * u, 13 * u);
        path.lineTo(7 * u, 19 * u);
        path.lineTo(9.6f * u, 17.9f * u);
        path.lineTo(6.8f * u, 12 * u);
        path.lineTo(12 * u, 12 * u);
        path.close();
        outline.setStrokeWidth(2 * d);
        invalidate();
    }

    void update(float x, float y, boolean shown, boolean pressed) {
        if (x == px && y == py && shown == this.shown && pressed == this.pressed) return;
        px = x;
        py = y;
        this.shown = shown;
        this.pressed = pressed;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shown) return;
        fill.setColor(pressed ? 0xFF7FD3FF : Color.WHITE);
        int save = canvas.save();
        canvas.translate(px, py);
        canvas.drawPath(path, outline);
        canvas.drawPath(path, fill);
        canvas.restoreToCount(save);
    }
}
