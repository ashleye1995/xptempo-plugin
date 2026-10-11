package com.xptempo.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

/** Small drawn icons for the side panel (RuneLite's RuneScape font has no tick, arrow or circle characters). */
final class GuideIcons
{
	static final ImageIcon DONE = draw(Kind.DONE, new Color(0x22, 0xC9, 0x9A));
	static final ImageIcon CURRENT = draw(Kind.CURRENT, new Color(0x22, 0xC9, 0x9A));
	static final ImageIcon TODO = draw(Kind.TODO, new Color(0x9A, 0xA4, 0x9F));
	static final ImageIcon FAILED = draw(Kind.FAILED, new Color(0xE8, 0xA2, 0x3B));

	private enum Kind
	{
		DONE, CURRENT, TODO, FAILED
	}

	private GuideIcons()
	{
	}

	private static ImageIcon draw(Kind kind, Color color)
	{
		BufferedImage img = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		switch (kind)
		{
			case DONE:
				g.drawPolyline(new int[]{2, 5, 10}, new int[]{6, 9, 3}, 3);
				break;
			case FAILED:
				g.drawLine(3, 3, 9, 9);
				g.drawLine(9, 3, 3, 9);
				break;
			case CURRENT:
				g.fillPolygon(new int[]{3, 10, 3}, new int[]{2, 6, 10}, 3);
				break;
			default:
				g.setStroke(new BasicStroke(1.5f));
				g.drawOval(2, 2, 8, 8);
				break;
		}
		g.dispose();
		return new ImageIcon(img);
	}
}
