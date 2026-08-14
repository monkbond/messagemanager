package nl.queuemanager.ui.message;

import nl.queuemanager.core.configuration.CoreConfiguration;
import nl.queuemanager.jms.impl.MessageFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;

import javax.jms.JMSException;
import javax.jms.TextMessage;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Formatted/Raw switch. Formatting inserts whitespace the message does not contain, so
 * the raw view is what tells the user what the message actually holds - it has to be exact,
 * and switching must not disturb another message the user has open.
 */
public class TestTextAreaContentViewer {

	private static final String RAW =
			"<envelope><payload><![CDATA[<inner><a>1</a></inner>]]></payload></envelope>";

	private Map<String, String> prefs;
	private TextMessageContentViewer viewer;

	@Before
	public void setUp() {
		prefs = new HashMap<String, String>();

		CoreConfiguration config = mock(CoreConfiguration.class);
		when(config.getUserPref(anyString(), anyString())).thenAnswer((Answer<String>) invocation -> {
			String key = invocation.getArgument(0);
			return prefs.containsKey(key) ? prefs.get(key) : (String)invocation.getArgument(1);
		});
		doAnswer(invocation -> {
			prefs.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(config).setUserPref(anyString(), anyString());

		viewer = new TextMessageContentViewer(config);
	}

	private static TextMessage message(String text) throws JMSException {
		TextMessage message = MessageFactory.createTextMessage();
		message.setText(text);
		return message;
	}

	private static <C> List<C> findAll(Component root, Class<C> type) {
		List<C> found = new ArrayList<C>();
		if(type.isInstance(root)) {
			found.add(type.cast(root));
		}
		if(root instanceof Container) {
			for(Component child: ((Container)root).getComponents()) {
				found.addAll(findAll(child, type));
			}
		}
		return found;
	}

	private static RSyntaxTextArea textAreaOf(JComponent ui) {
		return findAll(ui, RSyntaxTextArea.class).get(0);
	}

	private static AbstractButton button(JComponent ui, String text) {
		for(AbstractButton button: findAll(ui, AbstractButton.class)) {
			if(text.equals(button.getText())) {
				return button;
			}
		}
		throw new AssertionError("no button labelled " + text);
	}

	/**
	 * Look for the switch by its label rather than for any button at all: a scroll pane
	 * carries buttons of its own under some look and feels, so "there are no buttons" holds
	 * only by accident of the platform.
	 */
	private static boolean hasFormatSwitch(JComponent ui) {
		for(AbstractButton button: findAll(ui, AbstractButton.class)) {
			if("Formatted".equals(button.getText()) || "Raw".equals(button.getText())) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void formattedContentIsShownByDefault() throws Exception {
		JComponent ui = viewer.createUI(message(RAW));
		RSyntaxTextArea area = textAreaOf(ui);

		assertFalse(area.getText().equals(RAW));
		// The exact layout, including the payload that a serializer would have left on one
		// line because it sits inside a CDATA section.
		assertTrue(area.getText(), area.getText().contains(
				  "<payload><![CDATA[\n"
				+ "    <inner>\n"
				+ "      <a>1</a>\n"
				+ "    </inner>\n"
				+ "  ]]></payload>"));
		assertEquals(SyntaxConstants.SYNTAX_STYLE_XML, area.getSyntaxEditingStyle());
	}

	@Test
	public void theRawViewShowsTheMessageExactly() throws Exception {
		JComponent ui = viewer.createUI(message(RAW));
		RSyntaxTextArea area = textAreaOf(ui);

		button(ui, "Raw").doClick();
		assertEquals(RAW, area.getText());

		button(ui, "Formatted").doClick();
		assertFalse(area.getText().equals(RAW));
	}

	@Test
	public void theChosenViewIsRememberedForTheNextMessage() throws Exception {
		JComponent first = viewer.createUI(message(RAW));
		button(first, "Raw").doClick();

		assertEquals("true", prefs.get(TextAreaContentViewer.PREF_SHOW_RAW_CONTENT));

		JComponent second = viewer.createUI(message(RAW));
		assertEquals(RAW, textAreaOf(second).getText());
		assertTrue(button(second, "Raw").isSelected());
	}

	/** The message tree keeps a component per node, so several viewers can be alive at once. */
	@Test
	public void switchingOneViewerLeavesAnotherAlone() throws Exception {
		JComponent first = viewer.createUI(message(RAW));
		JComponent second = viewer.createUI(message(RAW));

		button(second, "Raw").doClick();

		assertEquals("the second switched", RAW, textAreaOf(second).getText());
		assertFalse("the first did not", textAreaOf(first).getText().equals(RAW));
	}

	@Test
	public void contentWithNothingToFormatGetsNoSwitch() throws Exception {
		JComponent ui = viewer.createUI(message("just some log output"));

		assertFalse(hasFormatSwitch(ui));
	}

	/** Malformed content keeps the switch, because the formatted view explains the problem. */
	/** The offset the failing character is painted at, in both views. */
	@Test
	public void theFailingPositionMapsToACharacter() {
		String text = "{\n  \"a\": 1,\n  \"b\": [1,2}\n}";

		// line 3 is '  "b": [1,2}' - the offending brace is its 12th character
		int offset = TextAreaContentViewer.offsetOf(text, 3, 12);
		assertEquals('}', text.charAt(offset));

		assertEquals("first character", 0, TextAreaContentViewer.offsetOf(text, 1, 1));
		assertEquals("no position known", -1, TextAreaContentViewer.offsetOf(text, 0, 0));
		assertEquals("empty content", -1, TextAreaContentViewer.offsetOf("", 1, 1));

		// Parsers often report the position just PAST the offending construct; that must
		// still land on a character rather than being dropped.
		assertEquals(text.length() - 1, TextAreaContentViewer.offsetOf(text, 4, 99));
		assertEquals("line beyond the end", -1, TextAreaContentViewer.offsetOf(text, 99, 1));
	}

	@Test
	public void theFailingCharacterIsMarkedInBothViews() throws Exception {
		String raw = "{\"a\":1,\"b\":[1,2}";
		JComponent ui = viewer.createUI(message(raw));
		RSyntaxTextArea area = textAreaOf(ui);

		// Formatted view: the content sits below the note, so the mark has to be shifted.
		int formatted = area.getHighlighter().getHighlights().length;
		assertTrue("a mark is painted in the formatted view", formatted > 0);
		int marked = area.getHighlighter().getHighlights()[formatted - 1].getStartOffset();
		assertEquals("marks the offending '}'", '}', area.getText().charAt(marked));

		button(ui, "Raw").doClick();
		int raws = area.getHighlighter().getHighlights().length;
		assertTrue("a mark is painted in the raw view", raws > 0);
		int rawMarked = area.getHighlighter().getHighlights()[raws - 1].getStartOffset();
		assertEquals("marks the offending '}'", '}', area.getText().charAt(rawMarked));
		assertEquals("which is the last character of this content", raw.length() - 1, rawMarked);
	}

	/** Every control character is marked, not just the first - they usually come in groups. */
	@Test
	public void everyControlCharacterIsMarked() throws Exception {
		JComponent ui = viewer.createUI(message("<r><a>3M\u00023P</a><b>x\u0000y</b></r>"));
		RSyntaxTextArea area = textAreaOf(ui);

		// Distinct positions: the first control character is ALSO where parsing failed, so it
		// carries the problem mark as well as the control mark.
		java.util.Set<Integer> marked = new java.util.HashSet<Integer>();
		for(javax.swing.text.Highlighter.Highlight h : area.getHighlighter().getHighlights()) {
			if(ContentFormatter.isNonPrintable(area.getText().charAt(h.getStartOffset()))) {
				marked.add(h.getStartOffset());
			}
		}
		assertEquals("both control characters marked", 2, marked.size());
	}

	@Test
	public void controlCharactersAreMarkedInTheRawViewToo() throws Exception {
		String raw = "<r><a>3M\u00023P</a></r>";
		JComponent ui = viewer.createUI(message(raw));
		RSyntaxTextArea area = textAreaOf(ui);

		button(ui, "Raw").doClick();
		assertEquals("the raw view is untouched", raw, area.getText());

		boolean marked = false;
		for(javax.swing.text.Highlighter.Highlight h : area.getHighlighter().getHighlights()) {
			if(area.getText().charAt(h.getStartOffset()) == '\u0002') {
				marked = true;
			}
		}
		assertTrue("the STX is marked in the raw view", marked);
	}

	/**
	 * A message that could not be formatted keeps whatever line structure it arrived with,
	 * and one held on a single enormous line hides its marks off to the right.
	 */
	@Test
	public void anEnormousLineIsWrappedSoTheMarksCanBeReached() {
		StringBuilder oneLongLine = new StringBuilder("<r>");
		while(oneLongLine.length() < 2000) {
			oneLongLine.append("<a>x</a>");
		}

		assertTrue(TextAreaContentViewer.hasUnreadablyLongLine(oneLongLine.toString()));
		assertFalse("formatted content is never wrapped",
				TextAreaContentViewer.hasUnreadablyLongLine("<a>\n  <b>1</b>\n</a>"));
		assertFalse("nor is short content", TextAreaContentViewer.hasUnreadablyLongLine("short"));
		assertFalse("nor is empty content", TextAreaContentViewer.hasUnreadablyLongLine(""));

		// A long line anywhere counts, not just the first.
		assertTrue(TextAreaContentViewer.hasUnreadablyLongLine("short\n" + oneLongLine));
	}

	/** Landing on the problem, not at the top - the point of the mark is to be seen. */
	@Test
	public void theViewLandsOnTheProblem() throws Exception {
		String raw = "{\"a\":1,\"b\":[1,2}";
		JComponent ui = viewer.createUI(message(raw));
		RSyntaxTextArea area = textAreaOf(ui);

		assertEquals("caret is on the offending character", '}',
				area.getText().charAt(area.getCaretPosition()));

		button(ui, "Raw").doClick();
		assertEquals("and again after switching view", '}',
				area.getText().charAt(area.getCaretPosition()));
	}

	/** Content that parses but carries a control character still has somewhere worth landing. */
	@Test
	public void theViewLandsOnAControlCharacterWhenThereIsNoParseFailure() throws Exception {
		JComponent ui = viewer.createUI(message("plain text with a \u0002 in it"));
		RSyntaxTextArea area = textAreaOf(ui);

		assertEquals('\u0002', area.getText().charAt(area.getCaretPosition()));
	}

	@Test
	public void contentWithNothingWrongStartsAtTheTop() throws Exception {
		JComponent ui = viewer.createUI(message(RAW));

		assertEquals(0, textAreaOf(ui).getCaretPosition());
	}

	@Test
	public void wellFormedContentIsNotMarked() throws Exception {
		JComponent ui = viewer.createUI(message(RAW));
		RSyntaxTextArea area = textAreaOf(ui);

		assertEquals(0, area.getHighlighter().getHighlights().length);
	}

	@Test
	public void malformedContentKeepsTheSwitchAndExplainsItself() throws Exception {
		JComponent ui = viewer.createUI(message("{\"a\":1,\"b\":[1,2}"));
		RSyntaxTextArea area = textAreaOf(ui);

		assertTrue(hasFormatSwitch(ui));
		assertTrue(area.getText().startsWith("//"));

		button(ui, "Raw").doClick();
		assertEquals("{\"a\":1,\"b\":[1,2}", area.getText());
	}
}
