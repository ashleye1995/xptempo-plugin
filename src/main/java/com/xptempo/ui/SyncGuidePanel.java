package com.xptempo.ui;

import com.xptempo.tracker.SyncGuide;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The "Get synced" checklist in RuneLite's side bar. Each row ticks itself off when the plugin sees the step done;
 * the step being highlighted in game is marked. Shown after pairing until everything is synced or the player hides it.
 */
public class SyncGuidePanel extends PluginPanel
{
	private static final Color ACCENT = new Color(0x22, 0xC9, 0x9A);
	private final JPanel rows = new JPanel(new GridLayout(0, 1, 0, 6));
	private final JLabel footer = new JLabel();

	public SyncGuidePanel(Runnable onHide)
	{
		setLayout(new BorderLayout(0, 10));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel title = new JLabel("Get synced");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		JLabel intro = new JLabel("<html>A few things only sync once you open them in game. Follow the green outline; each step ticks itself off.</html>");
		intro.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel top = new JPanel(new BorderLayout(0, 6));
		top.add(title, BorderLayout.NORTH);
		top.add(intro, BorderLayout.CENTER);
		add(top, BorderLayout.NORTH);

		rows.setOpaque(false);
		JPanel middle = new JPanel(new BorderLayout());
		middle.add(rows, BorderLayout.NORTH);
		add(middle, BorderLayout.CENTER);

		JButton hide = new JButton("Hide this guide");
		hide.setFocusable(false);
		hide.addActionListener(e -> onHide.run());
		footer.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel bottom = new JPanel(new BorderLayout(0, 6));
		bottom.add(footer, BorderLayout.NORTH);
		bottom.add(hide, BorderLayout.SOUTH);
		add(bottom, BorderLayout.SOUTH);
	}

	/** Redraws the checklist; safe to call from any thread. */
	public void show(List<SyncGuide.Row> steps, SyncGuide.Step current)
	{
		SwingUtilities.invokeLater(() ->
		{
			rows.removeAll();
			boolean all = true;
			for (SyncGuide.Row r : steps)
			{
				all &= r.done;
				rows.add(row(r, r.step == current));
			}
			footer.setText(steps.isEmpty()
				? "<html>Nothing to do: the switches that need a step are off.</html>"
				: all ? "<html>Everything is synced. You can hide this guide.</html>" : "");
			rows.revalidate();
			rows.repaint();
		});
	}

	private static JPanel row(SyncGuide.Row r, boolean current)
	{
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setBackground(current ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, current ? ACCENT : p.getBackground()),
			BorderFactory.createEmptyBorder(7, 8, 7, 8)));
		JLabel mark = new JLabel(r.done ? "✔" : current ? "▶" : "○");
		mark.setForeground(r.done || current ? ACCENT : ColorScheme.LIGHT_GRAY_COLOR);
		mark.setPreferredSize(new Dimension(14, 14));
		JLabel text = new JLabel("<html><b>" + r.title + "</b><br>" + r.detail + "</html>");
		text.setForeground(r.done ? ColorScheme.LIGHT_GRAY_COLOR : Color.WHITE);
		p.add(mark, BorderLayout.WEST);
		p.add(text, BorderLayout.CENTER);
		return p;
	}
}
