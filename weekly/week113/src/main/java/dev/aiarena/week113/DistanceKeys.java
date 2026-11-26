package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/** Dispatches local scripted key events through the game's ordinary F3 keyboard handler. */
public final class DistanceKeys {
    private static long activeWindow;
    private static boolean shift;
    private DistanceKeys() {}

    // GLFW normally supplies held modifier keys. This override exists only inside the
    // synchronous F key event for this one game window, never at the desktop level.
    public static Boolean held(long window, int key) {
        if (window == 0 || window != activeWindow) return null;
        if (key == GLFW.GLFW_KEY_F3) return true;
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT) return shift;
        return null;
    }

    public static void towards(MinecraftClient client, int chunks) {
        if (client.currentScreen != null || activeWindow != 0) return;
        long window = client.getWindow().getHandle();
        for (int tap = 0; tap < 3 && client.options.viewDistance != chunks; tap++) {
            int before = client.options.viewDistance;
            boolean decrease = before > chunks;
            int mods = decrease ? GLFW.GLFW_MOD_SHIFT : 0;
            try {
                activeWindow = window;
                shift = decrease;
                client.keyboard.onKey(window, GLFW.GLFW_KEY_F, 0, GLFW.GLFW_PRESS, mods);
                client.keyboard.onKey(window, GLFW.GLFW_KEY_F, 0, GLFW.GLFW_RELEASE, mods);
            } finally {
                activeWindow = 0;
                shift = false;
            }
            // The F3 release is handled by vanilla too, consuming the shortcut
            // without leaving the debug overlay on screen.
            client.keyboard.onKey(window, GLFW.GLFW_KEY_F3, 0, GLFW.GLFW_RELEASE, 0);
            int after = client.options.viewDistance;
            if (after != before + (decrease ? -1 : 1)) return;
            Race.recordDistance(client, before, after);
        }
    }
}
