package nl.queuemanager.ui.message;

import nl.queuemanager.jms.JMSPart;

import java.nio.charset.StandardCharsets;

class BytesPartViewer extends HexEditorContentViewer<JMSPart> implements MessagePartContentViewer {
	
	@Override
	public byte[] getContent(JMSPart part) {
		Object content = part.getContent();
		
		if(content != null) {
			if(byte[].class.isAssignableFrom(content.getClass())) {
				return (byte[])content;
			} else {
				// Explicit UTF-8 so the hex view shows the same bytes the save path
				// writes, instead of whatever the platform charset would produce.
				return content.toString().getBytes(StandardCharsets.UTF_8);
			}
		} else {
			return new byte[] {};
		}
	}

	public boolean supports(JMSPart object) {
		return true;
	}

	public String getDescription(JMSPart part) {
		return part.getContentType();
	}		
}
