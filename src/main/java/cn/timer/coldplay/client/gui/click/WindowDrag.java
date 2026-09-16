package cn.timer.coldplay.client.gui.click;

/** Header-drag offset and screen clamping for a movable window. */
final class WindowDrag {
    private boolean dragging;
    private int offsetX;
    private int offsetY;

    void begin(int mouseX, int mouseY, int x, int y) {
        dragging = true;
        offsetX = mouseX - x;
        offsetY = mouseY - y;
    }

    boolean isDragging() {
        return dragging;
    }

    /** Null when not dragging. clampWidth/clampHeight are the visible window size. */
    int[] update(int mouseX, int mouseY, int clampWidth, int clampHeight, int screenWidth, int screenHeight) {
        if (!dragging) {
            return null;
        }
        int newX = Math.clamp(mouseX - offsetX, 0, Math.max(0, screenWidth - clampWidth));
        int newY = Math.clamp(mouseY - offsetY, 0, Math.max(0, screenHeight - clampHeight));
        return new int[]{newX, newY};
    }

    void end() {
        dragging = false;
    }
}
