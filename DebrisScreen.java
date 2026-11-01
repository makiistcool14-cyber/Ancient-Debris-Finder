package dev.debrisfinder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class DebrisScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GRAY = 0xFFAAAAAA;

    public DebrisScreen() {
        super(Component.literal("Ancient Debris Finder"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 30;
        this.addRenderableWidget(Button.builder(Component.literal("Start"), b -> DebrisFinder.start())
            .bounds(cx - 105, y, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Stop"), b -> DebrisFinder.stop("Stopped by user"))
            .bounds(cx + 5, y, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int x = this.width / 2 - 150;
        int y = 20;
        g.drawString(this.font, "Ancient Debris Finder", x, y, WHITE);
        y += 16;
        g.drawString(this.font, "Seed: " + DebrisFinder.getSeedText(), x, y, GRAY);
        y += 12;
        g.drawString(this.font, "Status: " + DebrisFinder.getStatus(), x, y,
            DebrisFinder.isRunning() ? GREEN : YELLOW);
        y += 12;
        g.drawString(this.font, "Targets (green = confirmed, yellow = predicted):", x, y, GRAY);
        y += 12;
        List<DebrisFinder.Target> list = DebrisFinder.getDisplay();
        if (list.isEmpty()) {
            g.drawString(this.font, "none known yet", x, y, GRAY);
        }
        int shown = 0;
        for (DebrisFinder.Target t : list) {
            if (shown++ >= 12 || y > this.height - 45) break;
            g.drawString(this.font,
                (t.confirmed() ? "CONFIRMED " : "predicted  ") + DebrisFinder.fmt(t.pos()) + "  " + t.note(),
                x, y, t.confirmed() ? GREEN : YELLOW);
            y += 11;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false; // singleplayer keeps running while the GUI is open
    }
}
