package cn.timer.coldplay.client.util;

import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.util.ARGB;

/** Styling shared by the modules that draw through the vanilla gizmo pass. */
public final class Gizmo {

    private Gizmo() {
    }

    /** Strokes in {@code color}, filling with the same colour at {@code opacity} unless that is zero. */
    public static GizmoStyle style(int color, double width, double opacity) {
        float strokeWidth = (float) width;
        int alpha = (int) Math.round(opacity);
        return alpha == 0
                ? GizmoStyle.stroke(color, strokeWidth)
                : GizmoStyle.strokeAndFill(color, strokeWidth, ARGB.color(alpha, color));
    }
}
