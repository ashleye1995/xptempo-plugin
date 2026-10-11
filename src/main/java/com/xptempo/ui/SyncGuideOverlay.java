package com.xptempo.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.function.Supplier;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Outlines the one thing to click next for the "Get synced" guide (a side tab, a Character Summary row, a
 * collection log tab or page). It only draws; it never clicks or changes anything in the game.
 */
public class SyncGuideOverlay extends Overlay
{
	private static final Color ACCENT = new Color(0x22, 0xC9, 0x9A);
	private final Supplier<Rectangle> target;

	public SyncGuideOverlay(Supplier<Rectangle> target)
	{
		this.target = target;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Rectangle r = target.get();
		if (r == null || r.width <= 0 || r.height <= 0)
		{
			return null;
		}
		// A gentle pulse so the outline is easy to spot without being loud.
		double phase = (System.currentTimeMillis() % 1600) / 1600.0;
		int alpha = (int) (150 + 105 * Math.sin(phase * 2 * Math.PI));
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), Math.max(60, Math.min(255, alpha))));
		g.setStroke(new BasicStroke(2f));
		// Drawn just inside the target's edges: side tabs sit edge to edge, so anything outside spills onto the next one.
		g.drawRoundRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3, 8, 8);
		g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 28));
		g.fillRoundRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3, 8, 8);
		return null;
	}
}
