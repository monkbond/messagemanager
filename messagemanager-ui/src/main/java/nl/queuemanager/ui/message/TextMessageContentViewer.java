package nl.queuemanager.ui.message;

import nl.queuemanager.core.configuration.CoreConfiguration;
import nl.queuemanager.jms.JMSXMLMessage;

import jakarta.inject.Inject;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;

class TextMessageContentViewer extends TextAreaContentViewer<Message> implements MessageContentViewer {

	@Inject
	TextMessageContentViewer(CoreConfiguration config) {
		super(config);
	}

	/**
	 * The message text exactly as it arrived. Detecting whether it is XML or JSON, and
	 * formatting it, is {@link ContentFormatter}'s job - this viewer must keep hold of the
	 * original so the user can switch back to it.
	 */
	@Override
	public String getContent(Message message) {
		try {
			// An XML message that carries a Document rather than text serializes itself here.
			return ((TextMessage)message).getText();
		} catch (JMSException e) {
			return "Exception while retrieving the contents of the message.\n" +
				e.toString();
		}
	}

	public boolean supports(Message message) {
		return JMSXMLMessage.class.isAssignableFrom(message.getClass())
			|| TextMessage.class.isAssignableFrom(message.getClass());
	}

	public String getDescription(Message message) {
		if(message instanceof JMSXMLMessage)
			return "Xml";
		return "Text";
	}
}
