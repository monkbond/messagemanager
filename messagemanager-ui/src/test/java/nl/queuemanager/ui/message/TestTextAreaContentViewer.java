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
