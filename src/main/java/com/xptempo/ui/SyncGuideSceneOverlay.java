package com.xptempo.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Collection;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * For the guide's "Open your bank" step: outlines every bank booth, bank chest and banker in view, in the same
 * green as the interface outline. It only draws.
 */
public class SyncGuideSceneOverlay extends Overlay
{
	private static final Color ACCENT = new Color(0x22, 0xC9, 0x9A);
	private final ModelOutlineRenderer outlines;
	private final BooleanSupplier active;
	private final Supplier<Collection<TileObject>> objects;
	private final Supplier<Collection<NPC>> npcs;

	public SyncGuideSceneOverlay(ModelOutlineRenderer outlines, BooleanSupplier active, Supplier<Collection<TileObject>> objects, Supplier<Collection<NPC>> npcs)
	{
		this.outlines = outlines;
		this.active = active;
		this.objects = objects;
		this.npcs = npcs;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!active.getAsBoolean())
		{
			return null;
		}
		for (TileObject o : objects.get())
		{
			outlines.drawOutline(o, 2, ACCENT, 4);
		}
		for (NPC n : npcs.get())
		{
			outlines.drawOutline(n, 2, ACCENT, 4);
		}
		return null;
	}
}
