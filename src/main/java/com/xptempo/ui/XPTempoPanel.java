package com.xptempo.ui;

import com.xptempo.XPTempoConfig;
import com.xptempo.tracker.SyncGuide;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * XPTempo's side panel, always there: status, the uploads switch (with the same warning as the settings),
 * pairing, "What to send", and the "Get synced" checklist. Everything is stored in the plugin's own config, so
 * this panel and RuneLite's settings page always agree.
 */
public class XPTempoPanel extends PluginPanel
{
	private static final Color ACCENT = new Color(0x22, 0xC9, 0x9A);

	/** What the plugin knows right now, for the status line. */
	public static final class Status
	{
		final boolean enabled;
		final boolean paired;
		final String character;

		public Status(boolean enabled, boolean paired, String character)
		{
			this.enabled = enabled;
			this.paired = paired;
			this.character = character;
		}
	}

	private final ConfigManager configManager;
	private final XPTempoConfig config;
	private boolean updating;

	private final JLabel status = new JLabel();
	private final JCheckBox enabled = new JCheckBox("Enable XPTempo");
	private final JPanel pairing = new JPanel();
	private final JTextField code = new JTextField();
	private final JCheckBox inventory = new JCheckBox("Inventory and equipment");
	private final JCheckBox bank = new JCheckBox("Bank");
	private final JCheckBox ge = new JCheckBox("Grand Exchange offers");
	private final JCheckBox loot = new JCheckBox("Drops and kill counts");
	private final JCheckBox showGuide = new JCheckBox("Show sync guide");
	private final JPanel guideRows = new JPanel();
	private final JLabel guideNote = new JLabel();

	public XPTempoPanel(ConfigManager configManager, XPTempoConfig config)
	{
		this.configManager = configManager;
		this.config = config;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("XPTempo");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		body.add(left(title));
		body.add(Box.createVerticalStrut(4));
		body.add(left(status));
		body.add(Box.createVerticalStrut(10));

		enabled.addActionListener(e -> onEnabled());
		body.add(section("Uploads", enabled));

		pairing.setLayout(new BoxLayout(pairing, BoxLayout.Y_AXIS));
		pairing.setOpaque(false);
		body.add(section("Pairing", pairing));

		inventory.addActionListener(e -> set("uploadInventory", inventory.isSelected()));
		bank.addActionListener(e -> set("uploadBank", bank.isSelected()));
		ge.addActionListener(e -> set("uploadGe", ge.isSelected()));
		loot.addActionListener(e -> set("uploadLoot", loot.isSelected()));
		body.add(section("What to send", inventory, bank, ge, loot));

		showGuide.addActionListener(e -> set(XPTempoConfig.KEY_SHOW_GUIDE, showGuide.isSelected()));
		guideRows.setLayout(new BoxLayout(guideRows, BoxLayout.Y_AXIS));
		guideRows.setOpaque(false);
		guideNote.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		body.add(section("Get synced", showGuide, guideRows, guideNote));

		JButton site = new JButton("Open xptempo.com");
		site.setFocusable(false);
		site.addActionListener(e -> LinkBrowser.browse("https://xptempo.com"));
		body.add(Box.createVerticalStrut(6));
		body.add(left(site));

		add(body, BorderLayout.NORTH);
	}

	/** Redraws from the config and the plugin's state; safe from any thread. */
	public void update(Status s, List<SyncGuide.Row> steps, SyncGuide.Step current)
	{
		SwingUtilities.invokeLater(() ->
		{
			updating = true;
			try
			{
				if (!s.enabled)
				{
					status.setText("<html>Uploads are off.</html>");
				}
				else if (!s.paired)
				{
					status.setText("<html>Not paired yet: get a pairing code from the XPTempo app.</html>");
				}
				else if (s.character != null)
				{
					status.setText("<html>Syncing <b>" + escape(s.character) + "</b>.</html>");
				}
				else
				{
					status.setText("<html>Paired. Log in to sync.</html>");
				}
				status.setForeground(s.enabled && s.paired ? ACCENT : ColorScheme.LIGHT_GRAY_COLOR);

				enabled.setSelected(config.enabled());
				inventory.setSelected(config.uploadInventory());
				bank.setSelected(config.uploadBank());
				ge.setSelected(config.uploadGe());
				loot.setSelected(config.uploadLoot());
				showGuide.setSelected(config.showSyncGuide());
				buildPairing(s.paired);
				buildGuide(steps, current, config.showSyncGuide());
			}
			finally
			{
				updating = false;
			}
			revalidate();
			repaint();
		});
	}

	private void onEnabled()
	{
		if (updating)
		{
			return;
		}
		if (enabled.isSelected())
		{
			// The same consent as RuneLite's settings page shows for this switch.
			int ok = JOptionPane.showConfirmDialog(this, XPTempoConfig.ENABLE_WARNING, "Enable XPTempo",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (ok != JOptionPane.OK_OPTION)
			{
				enabled.setSelected(false);
				return;
			}
		}
		set(XPTempoConfig.KEY_ENABLED, enabled.isSelected());
	}

	private void buildPairing(boolean paired)
	{
		pairing.removeAll();
		if (paired)
		{
			JLabel l = new JLabel("<html>This RuneLite profile is paired with your XPTempo account.</html>");
			l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			JButton unpair = new JButton("Unpair");
			unpair.setFocusable(false);
			unpair.addActionListener(e ->
			{
				int ok = JOptionPane.showConfirmDialog(this,
					"<html>Unpair this RuneLite profile? Uploads stop until you pair again with a new code.<br>"
						+ "To remove the key from your account too, revoke it in the app's Settings.</html>",
					"Unpair", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
				if (ok == JOptionPane.OK_OPTION)
				{
					configManager.setConfiguration(XPTempoConfig.GROUP, XPTempoConfig.KEY_API_KEY, "");
				}
			});
			pairing.add(left(l));
			pairing.add(Box.createVerticalStrut(6));
			pairing.add(left(unpair));
			return;
		}
		JLabel l = new JLabel("<html>In the app choose <b>Link a character</b>, then paste the 8-character code here.</html>");
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		code.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		JButton pair = new JButton("Pair");
		pair.setFocusable(false);
		pair.addActionListener(e ->
		{
			String c = code.getText().trim();
			if (c.isEmpty())
			{
				return;
			}
			if (!config.enabled())
			{
				JOptionPane.showMessageDialog(this, "Switch on \"Enable XPTempo\" first.", "Pair", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			configManager.setConfiguration(XPTempoConfig.GROUP, XPTempoConfig.KEY_PAIRING_CODE, c);
			code.setText("");
		});
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.add(code, BorderLayout.CENTER);
		row.add(pair, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		pairing.add(left(l));
		pairing.add(Box.createVerticalStrut(6));
		pairing.add(left(row));
	}

	private void buildGuide(List<SyncGuide.Row> steps, SyncGuide.Step current, boolean shown)
	{
		guideRows.removeAll();
		if (!shown)
		{
			guideNote.setText("<html>Switched off. Tick it to see what is left to sync.</html>");
			return;
		}
		boolean all = true;
		for (SyncGuide.Row r : steps)
		{
			all &= r.done;
			guideRows.add(row(r, r.step == current));
			guideRows.add(Box.createVerticalStrut(6));
		}
		guideNote.setText(steps.isEmpty()
			? "<html>Nothing to do: the switches that need a step are off.</html>"
			: all ? "<html>Everything is synced.</html>" : "<html>Follow the green outline in game; each step ticks itself off.</html>");
	}

	private static JPanel row(SyncGuide.Row r, boolean current)
	{
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setBackground(current ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, current ? ACCENT : p.getBackground()),
			BorderFactory.createEmptyBorder(7, 8, 7, 8)));
		JLabel mark = new JLabel(r.done ? "✔" : current ? "▶" : "○");
		mark.setForeground(r.done || current ? ACCENT : ColorScheme.LIGHT_GRAY_COLOR);
		JLabel text = new JLabel("<html><b>" + r.title + "</b><br>" + r.detail + "</html>");
		text.setForeground(r.done ? ColorScheme.LIGHT_GRAY_COLOR : Color.WHITE);
		p.add(mark, BorderLayout.WEST);
		p.add(text, BorderLayout.CENTER);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height + 20));
		return p;
	}

	private void set(String key, boolean value)
	{
		if (!updating)
		{
			configManager.setConfiguration(XPTempoConfig.GROUP, key, value);
		}
	}

	private static JPanel section(String name, Component... parts)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JLabel heading = new JLabel(name);
		heading.setFont(FontManager.getRunescapeSmallFont());
		heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		p.add(left(heading));
		p.add(Box.createVerticalStrut(4));
		for (Component c : parts)
		{
			if (c instanceof JCheckBox)
			{
				((JCheckBox) c).setOpaque(false);
				((JCheckBox) c).setFocusable(false);
			}
			p.add(left(c));
		}
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		wrap.add(p, BorderLayout.CENTER);
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		return wrap;
	}

	private static <T extends Component> T left(T c)
	{
		if (c instanceof javax.swing.JComponent)
		{
			((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		return c;
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
