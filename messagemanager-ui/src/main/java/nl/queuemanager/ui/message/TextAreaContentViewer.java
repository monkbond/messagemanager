package nl.queuemanager.ui.message;

import com.google.common.eventbus.Subscribe;
import nl.queuemanager.core.configuration.CoreConfiguration;
import nl.queuemanager.ui.GlobalHighlightEvent;
import nl.queuemanager.ui.util.JSearchableTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

abstract class TextAreaContentViewer<T> implements ContentViewer<T> {

	/**
	 * Remembered so a user who prefers to work with the raw message is not switching back
	 * on every message they open.
	 */
	static final String PREF_SHOW_RAW_CONTENT = "messageViewerShowRawContent";

	private final CoreConfiguration config;

	private RSyntaxTextArea textArea;
	private String highlightString;

	protected TextAreaContentViewer(CoreConfiguration config) {
		this.config = config;
	}

	/** The content of the object exactly as it arrived, without any formatting applied. */
	protected abstract String getContent(T object);

	public JComponent createUI(T object) {
		final ContentFormatter.Content content = ContentFormatter.analyze(getContent(object));

		final RSyntaxTextArea area = createTextArea(content);
		textArea = area;

		final RTextScrollPane scrollPane = new RTextScrollPane(area);
		if(!content.isFormattable()) {
			// Nothing to switch between - do not offer a switch that does nothing.
			return scrollPane;
		}

		JPanel panel = new JPanel(new BorderLayout());
		panel.add(createFormatSwitch(area, content), BorderLayout.NORTH);
		panel.add(scrollPane, BorderLayout.CENTER);
		return panel;
	}

	protected RSyntaxTextArea createTextArea(ContentFormatter.Content content) {
		final RSyntaxTextArea area = new JSearchableTextArea();
		area.setSyntaxEditingStyle(content.getKind().getSyntaxStyle());
		// Folding needs a structure to fold; on content that did not parse it would only
		// produce misleading fold points.
		area.setCodeFoldingEnabled(content.getKind() != ContentFormatter.Kind.PLAIN
				&& !content.isMalformed());
		area.setEditable(false);
		showContent(area, content, isShowRawByDefault());
		return area;
	}

	/**
	 * The Formatted/Raw switch. Formatting inserts whitespace the message does not contain -
	 * inside a CDATA section that whitespace is significant - so the raw view is what tells
	 * the user what the message actually holds.
	 */
	private JComponent createFormatSwitch(final RSyntaxTextArea area, final ContentFormatter.Content content) {
		final boolean showRaw = isShowRawByDefault();

		final JToggleButton formattedButton = new JToggleButton("Formatted", !showRaw);
		formattedButton.setToolTipText(content.isMalformed()
				? "Explain why this content cannot be formatted"
				: "Show the " + content.getKind() + " content indented for reading");
		formattedButton.addActionListener(e -> {
			setShowRawByDefault(false);
			showContent(area, content, false);
		});

		final JToggleButton rawButton = new JToggleButton("Raw", showRaw);
		rawButton.setToolTipText("Show the content exactly as it is in the message");
		rawButton.addActionListener(e -> {
			setShowRawByDefault(true);
			showContent(area, content, true);
		});

		ButtonGroup group = new ButtonGroup();
		group.add(formattedButton);
		group.add(rawButton);

		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		panel.add(formattedButton);
		panel.add(Box.createRigidArea(new Dimension(3, 0)));
		panel.add(rawButton);
		return panel;
	}

	private void showContent(RSyntaxTextArea area, ContentFormatter.Content content, boolean raw) {
		area.setText(raw ? content.getRaw() : content.getFormatted());
		area.setCaretPosition(0);

		// Switching replaces the document, which drops the marks the global highlight put
		// on it. Re-apply them so a highlight survives a switch.
		markHighlight(area);
	}

	private boolean isShowRawByDefault() {
		return Boolean.parseBoolean(config.getUserPref(PREF_SHOW_RAW_CONTENT, "false"));
	}

	private void setShowRawByDefault(boolean showRaw) {
		config.setUserPref(PREF_SHOW_RAW_CONTENT, Boolean.toString(showRaw));
	}

	@Subscribe
	public void onGlobalHighlightEvent(GlobalHighlightEvent e) {
		highlightString = e.getHighlightString();
		markHighlight(textArea);
	}

	private void markHighlight(RSyntaxTextArea area) {
		if(area == null || highlightString == null) {
			return;
		}

		SearchContext context = new SearchContext();
		context.setSearchFor(highlightString);
		context.setMatchCase(false);
		context.setRegularExpression(false);
		context.setSearchForward(true);
		context.setWholeWord(false);
		SearchEngine.markAll(area, context);
	}

}
