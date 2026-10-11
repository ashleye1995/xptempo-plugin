package com.xptempo.ui;

import com.xptempo.XPTempoConfig;
import com.xptempo.tracker.SyncGuide;
import com.xptempo.tracker.SyncStatus;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.Timer;
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

	/** Live upload state for the "Sync" section, read every second. */
	public static final class SyncInfo
	{
		final SyncStatus.Snapshot snapshot;
		final long nextUploadAt;
		final boolean live;

		public SyncInfo(SyncStatus.Snapshot snapshot, long nextUploadAt, boolean live)
		{
			this.snapshot = snapshot;
			this.nextUploadAt = nextUploadAt;
			this.live = live;
		}
	}

	private static final Color AMBER = new Color(0xE8, 0xA2, 0x3B);
	private final Supplier<SyncInfo> syncInfo;
	private final JPanel syncRows = new JPanel();
	private final Timer ticker;
	/** The per-kind list starts folded; the last/next upload lines always show. */
	private boolean syncExpanded;
	private final JButton syncToggle = new JButton();

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
	private final JButton guideToggle = new JButton();
	/** Once everything is synced the section folds to one line; the player can open it again. */
	private boolean guideExpanded;
	private List<SyncGuide.Row> lastSteps = java.util.Collections.emptyList();
	private SyncGuide.Step lastCurrent;

	public XPTempoPanel(ConfigManager configManager, XPTempoConfig config, Supplier<SyncInfo> syncInfo)
	{
		this.configManager = configManager;
		this.config = config;
		this.syncInfo = syncInfo;
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

		syncRows.setLayout(new BoxLayout(syncRows, BoxLayout.Y_AXIS));
		syncRows.setOpaque(false);
		syncToggle.setFocusable(false);
		syncToggle.addActionListener(e ->
		{
			syncExpanded = !syncExpanded;
			renderSync();
		});
		body.add(section("Sync", syncRows, syncToggle));
		ticker = new Timer(1000, e -> renderSync());
		ticker.start();

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
		guideToggle.setFocusable(false);
		guideToggle.addActionListener(e ->
		{
			guideExpanded = !guideExpanded;
			buildGuide(lastSteps, lastCurrent, config.showSyncGuide());
			revalidate();
			repaint();
		});
		body.add(section("Get synced", showGuide, guideRows, guideNote, guideToggle));

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

	/** Stops the once-a-second refresh (the plugin calls this when it shuts down). */
	public void dispose()
	{
		ticker.stop();
	}

	/** The "Sync" section: last and next upload, any problem, and when each kind of data was last sent. */
	private void renderSync()
	{
		SyncInfo info = syncInfo.get();
		syncRows.removeAll();
		long now = System.currentTimeMillis();
		if (!config.enabled() || config.apiKey().trim().isEmpty())
		{
			syncRows.add(left(grey("Nothing is sent until uploads are on and this profile is paired.")));
			syncToggle.setVisible(false);
		}
		else
		{
			SyncStatus.Snapshot s = info.snapshot;
			syncRows.add(left(line("Last upload", s.lastUploadAt == 0 ? "not yet" : SyncStatus.ago(s.lastUploadAt, now), Color.WHITE)));
			if (info.live)
			{
				long in = Math.max(0, (info.nextUploadAt - now) / 1000);
				syncRows.add(left(line("Next upload", in == 0 ? "now" : "in " + in + "s", Color.WHITE)));
			}
			else
			{
				syncRows.add(left(line("Next upload", "when you log in", ColorScheme.LIGHT_GRAY_COLOR)));
			}
			if (s.problem != null)
			{
				JLabel p = new JLabel("<html>" + escape(s.problem) + "</html>");
				p.setForeground(AMBER);
				syncRows.add(Box.createVerticalStrut(4));
				syncRows.add(left(p));
			}
			syncToggle.setVisible(true);
			syncToggle.setText(syncExpanded ? "Hide details" : "Show details");
			if (syncExpanded)
			{
				syncRows.add(Box.createVerticalStrut(6));
			}
			for (SyncStatus.Kind k : syncExpanded ? SyncStatus.Kind.values() : new SyncStatus.Kind[0])
			{
				boolean on = k == SyncStatus.Kind.INVENTORY ? config.uploadInventory()
					: k == SyncStatus.Kind.BANK ? config.uploadBank()
					: k == SyncStatus.Kind.GE ? config.uploadGe()
					: k == SyncStatus.Kind.DROPS || k == SyncStatus.Kind.COLLECTION_LOG ? config.uploadLoot()
					: true;
				Long at = s.lastSent.get(k);
				int queued = s.waiting.getOrDefault(k, 0);
				String value = !on ? "off"
					: queued > 0 ? "waiting (" + queued + ")"
					: at != null ? SyncStatus.ago(at, now) + (k == SyncStatus.Kind.COLLECTION_LOG && s.clogPages > 0 ? " (" + s.clogPages + ")" : "")
					: k.hint;
				Color c = !on ? ColorScheme.LIGHT_GRAY_COLOR : queued > 0 ? Color.WHITE : at != null ? ACCENT : ColorScheme.LIGHT_GRAY_COLOR;
				syncRows.add(left(line(k.label, value, c)));
			}
		}
		syncRows.revalidate();
		syncRows.repaint();
	}

	/** "Label ......... value" on one line. */
	private static JPanel line(String label, String value, Color valueColor)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		JLabel l = new JLabel(label);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JLabel v = new JLabel(value);
		v.setForeground(valueColor);
		row.add(l, BorderLayout.WEST);
		row.add(v, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static JLabel grey(String text)
	{
		JLabel l = new JLabel("<html>" + text + "</html>");
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
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
		lastSteps = steps;
		lastCurrent = current;
		guideRows.removeAll();
		boolean complete = !steps.isEmpty() && steps.stream().allMatch(r -> r.done);
		if (!complete)
		{
			guideExpanded = false; // a new step (or a reset) opens it again on its own
		}
		boolean folded = complete && !guideExpanded;
		showGuide.setVisible(!folded);
		guideToggle.setVisible(complete);
		guideToggle.setText(folded ? "Show details" : "Hide details");
		guideNote.setIcon(folded ? GuideIcons.DONE : null);
		guideNote.setIconTextGap(6);
		if (folded)
		{
			guideNote.setText("Everything is synced.");
			return;
		}
		if (!shown)
		{
			guideNote.setText("<html>Switched off. Tick it to see what is left to sync.</html>");
			return;
		}
		for (SyncGuide.Row r : steps)
		{
			guideRows.add(row(r, r.step == current));
			guideRows.add(Box.createVerticalStrut(6));
		}
		guideNote.setText(steps.isEmpty()
			? "<html>Nothing to do: the switches that need a step are off.</html>"
			: complete ? "<html>Everything is synced.</html>" : "<html>Follow the green outline in game; each step ticks itself off.</html>");
	}

	private static JPanel row(SyncGuide.Row r, boolean current)
	{
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setBackground(current ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, current ? ACCENT : p.getBackground()),
			BorderFactory.createEmptyBorder(7, 8, 7, 8)));
		JLabel mark = new JLabel(r.done ? GuideIcons.DONE : current ? GuideIcons.CURRENT : GuideIcons.TODO);
		mark.setVerticalAlignment(JLabel.TOP);
		mark.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
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
