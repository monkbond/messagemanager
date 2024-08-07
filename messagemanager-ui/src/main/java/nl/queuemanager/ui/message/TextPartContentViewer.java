package nl.queuemanager.ui.message;

import nl.queuemanager.jms.JMSPart;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;

class TextPartContentViewer extends XmlContentViewer<JMSPart> implements MessagePartContentViewer {
	
	@Override
	public String getContent(JMSPart part) {
		String content = (String)part.getContent();
		if(content != null) {
			try {
				InputSource is = new InputSource(new StringReader(content));
				Document doc = DocumentBuilderFactory.newInstance().
					newDocumentBuilder().parse(is);
				
				return formatXml(doc);
			} catch (SAXException e) {
				return content;
			} catch (IOException e) {
				return content;
			} catch (ParserConfigurationException e) {
				return content;
			}
		} else {
			return "";
		}
	}

	public boolean supports(JMSPart part) {
		String contentType = part.getContentType();
		
		return contentType.startsWith("text/")
				|| contentType.startsWith("application/x-sonicxq-bpheader")
				|| contentType.startsWith("application/x-sonicxq-")
				|| contentType.startsWith("application/xml")
				|| contentType.startsWith("application/json");
	}

	public String getDescription(JMSPart part) {
		return part.getContentType();
	}		
}
