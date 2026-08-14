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
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.BorderLayout;
import java.awt.Color;

abstract class TextAreaContentViewer<T> implements ContentViewer<T> {

	/**
	 * Remembered so a user who prefers to work with the raw message is not switching back
	 * on every message they open.
	 */
	static final String PREF_SHOW_RAW_CONTENT = "messageViewerShowRawContent";

	/**
	 * Marks the character that broke parsing. Translucent so it reads as a marker on both
	 * the light and the dark editor theme, and leaves the character itself legible.
	 */
	private static final Highlighter.HighlightPainter PROBLEM_PAINTER =
			new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 96, 96, 128));

	/**
	 * Marks a non-printable character. A different colour from the parse failure: these are
	 * often its cause, but they are worth seeing in content that parses perfectly well too.
	 */
	private static final Highlighter.HighlightPainter CONTROL_PAINTER =
			new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 170, 0, 150));

	/**
	 * Enough to show a message is riddled with them without painting tens of thousands of
	 * marks over content that is really binary.
	 */
	private static final int MAX_CONTROL_MARKS = 500;

	/** Beyond this, a line is wrapped rather than left to run off the right-hand side. */
	private static final int LONG_LINE = 500;

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

		final JRadioButton formattedButton = new JRadioButton("Formatted", !showRaw);
		formattedButton.setToolTipText(content.isMalformed()
				? "Explain why this content cannot be formatted"
				: "Show the " + content.getKind() + " content indented for reading");
		formattedButton.addActionListener(e -> {
			setShowRawByDefault(false);
			showContent(area, content, false);
		});

		final JRadioButton rawButton = new JRadioButton("Raw", showRaw);
		rawButton.setToolTipText("Show the content exactly as it is in the message");
		rawButton.addActionListener(e -> {
			setShowRawByDefault(true);
			showContent(area, content, true);
		});

		ButtonGroup group = new ButtonGroup();
		group.add(formattedButton);
		group.add(rawButton);

		// Same shape as the "Message Content:" choice on the message sender: a label
		// followed by the alternatives, laid out along the x axis.
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		panel.add(new JLabel("View: "));
		panel.add(formattedButton);
		panel.add(rawButton);
		panel.add(Box.createHorizontalGlue());
		return panel;
	}

	private void showContent(RSyntaxTextArea area, ContentFormatter.Content content, boolean raw) {
		final String text = raw ? content.getRaw() : content.getFormatted();
		area.setText(text);

		// Content that could not be formatted keeps whatever line structure it arrived with,
		// and a message held on one enormous line is unreadable - and hides its marks off to
		// the right where no amount of looking will find them.
		area.setLineWrap(hasUnreadablyLongLine(text));

		// Switching replaces the document, which drops the marks the global highlight put
		// on it. Re-apply them so a highlight survives a switch.
		markHighlight(area);
		final int firstControl = markControlCharacters(area);
		final int problem = markProblem(area, content, raw);

		// Land on what is wrong rather than at the top. A mark is no use in a message held on
		// one ten-thousand-character line if finding it means scrolling there first.
		final int interesting = problem >= 0 ? problem : firstControl;
		area.setCaretPosition(interesting >= 0 ? interesting : 0);
	}

	/**
	 * Mark EVERY non-printable character in what is on screen, returning the first position
	 * marked so the view can be taken there.
	 * <p>
	 * Marking EVERY non-printable character matters: they have no visible form, so without
	 * this a message that will not parse - or one carrying a stray control character through
	 * an interface - looks perfectly ordinary.
	 * <p>
	 * The scan runs over the displayed text rather than over the raw content, so it is right
	 * in both views without having to map positions through the formatting.
	 */
	private int markControlCharacters(RSyntaxTextArea area) {
		final String text = area.getText();
		int marked = 0;
		int first = -1;

		for(int i = 0; i < text.length() && marked < MAX_CONTROL_MARKS; i++) {
			if(!ContentFormatter.isNonPrintable(text.charAt(i))) {
				continue;
			}
			try {
				area.getHighlighter().addHighlight(i, i + 1, CONTROL_PAINTER);
				marked++;
				if(first < 0) {
					first = i;
				}
			} catch (BadLocationException e) {
				break; // the document changed under us; nothing useful to report
			}
		}
		return first;
	}

	/**
	 * Paint the character that broke parsing, so it can be found at a glance instead of by
	 * counting to the line and column the note mentions.
	 *
	 * @return the position marked, or -1 when there is nothing to mark
	 */
	private int markProblem(RSyntaxTextArea area, ContentFormatter.Content content, boolean raw) {
		if(!content.isMalformed()) {
			return -1;
		}

		int offset = offsetOf(content.getRaw(), content.getProblemLine(), content.getProblemColumn());
		if(offset < 0) {
			return -1;
		}

		// The position is relative to the RAW content. The formatted view shows the same
		// content with the explanatory note in front of it, so shift by that much.
		if(!raw) {
			offset += area.getText().length() - content.getRaw().length();
		}

		try {
			area.getHighlighter().addHighlight(offset, offset + 1, PROBLEM_PAINTER);
			return offset;
		} catch (BadLocationException e) {
			// The position the parser reported is not in the text after all - the note still
			// names it, so there is nothing worth reporting to the user here.
			return -1;
		}
	}

	/**
	 * Whether any line is long enough that reading it means scrolling sideways rather than
	 * down. Formatted content never is; a one-line message that could not be formatted is.
	 */
	static boolean hasUnreadablyLongLine(String text) {
		int lineStart = 0;
		while(lineStart <= text.length()) {
			int newline = text.indexOf('\n', lineStart);
			int end = newline < 0 ? text.length() : newline;
			if(end - lineStart > LONG_LINE) {
				return true;
			}
			if(newline < 0) {
				return false;
			}
			lineStart = newline + 1;
		}
		return false;
	}

	/**
	 * The offset of a 1-based line/column in the given text, or -1 when it does not point at
	 * a character. Parsers report the position AFTER the offending construct as often as the
	 * construct itself, so an offset past the end is pulled back onto the last character
	 * rather than dropped.
	 */
	static int offsetOf(String text, int line, int column) {
		if(line <= 0 || text.isEmpty()) {
			return -1;
		}

		int offset = 0;
		for(int current = 1; current < line; current++) {
			final int newline = text.indexOf('\n', offset);
			if(newline < 0) {
				return -1;
			}
			offset = newline + 1;
		}

		offset += Math.max(0, column - 1);
		return Math.min(offset, text.length() - 1);
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
