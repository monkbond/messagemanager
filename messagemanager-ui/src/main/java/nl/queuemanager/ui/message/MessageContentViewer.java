package nl.queuemanager.ui.message;

import javax.jms.Message;
import javax.swing.JComponent;

public interface MessageContentViewer extends ContentViewer<Message> {
	
	/**
	 * Examines the message and determines whether this Content Viewer supports 
	 * displaying the message content.
	 * 
	 * @param message
	 * @return
	 */
	public boolean supports(Message message);

	/**
	 * Create and return the user interface component for this message.
	 * 
	 * @return
	 */
	public JComponent createUI(Message message);
}
