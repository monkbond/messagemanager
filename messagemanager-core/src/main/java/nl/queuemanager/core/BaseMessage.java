/**

 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.queuemanager.core;

import nl.queuemanager.jms.JMSMultipartMessage;
import nl.queuemanager.jms.JMSPart;
import nl.queuemanager.jms.MessageType;
import nl.queuemanager.jms.impl.MessageFactory;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.jms.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.util.Base64;
import java.util.Enumeration;

/**
 * This class reads and writes .esbmsg files. 
 * 
 * These files have the following structure:<br>
 * <pre>
 *  &lt;" + getQualifiedMessageElementName() + " type="Multipart-Message" xmlns:sonic_esbmsg="http://sonicsw.com/tools/esbmsg/namespace">
 *		&lt;header name="JMSCorrelationID" value="Header-Value">&lt;/header>
 *		&lt;header name="JMSReplyTo" value="dev.MessageListener">&lt;/header>
 *		&lt;header name="JMSType" value='Multipart Message'>&lt;/header>
 *		&lt;property name="new-name0" value="new-value">&lt;/property>
 *		&lt;property name="new-name1" value="new-value">&lt;/property>
 *		&lt;property name="new-name2" value="new-value">&lt;/property>
 *
 *		&lt;part content-type="text/xml" content-id="new-content-id0" file-ref="" use-file-ref="false">&lt;/part>
 *		&lt;part content-type="text/xml" content-id="new-content-id1" file-ref="" use-file-ref="false">&lt;/part>
 *		&lt;part content-type="text/xml" content-id="new-content-id2" file-ref="" use-file-ref="false">&lt;/part>
 *
 *		or
 *
 *      &lt;body content-type="text/plain" content-id="body-part" file-ref="sonicfs:///workspace/Test/multipart.esbmsg" use-file-ref="true">&lt;/body>
 *	&lt;/" + getQualifiedMessageElementName() + ">
 * </pre>
 * 
 * @author Gerco Dries (gdr@progaia-rs.nl)
 *
 */
public abstract class BaseMessage implements SaveableMessage {


	private static final String CONTENT_ANYTEXT = "text/";
	private static String[] contentIds;

	private static final String HEADER_JMSREPLYTO = "JMSReplyTo";
	private static final String HEADER_JMSCORRELATIONID = "JMSCorrelationID";

	private static final String PROPERTY_EXTENDED_TYPE = "JMS_SonicMQ_ExtendedType";
	private static final String PROPERTY_CONTENT_TYPE = "Content-Type";

	private static final XPathFactory xpathFactory = XPathFactory.newInstance();
	private static final MapNamespaceContext namespaceContext = new MapNamespaceContext();


	// in order to keep the static approach we need these methods implemented in the subclasses
	protected abstract String getMessageNamespace();
	protected abstract String getQualifiedMessageElementName();

	protected void initialize(String prefix, String namespace) {
		namespaceContext.add(prefix, namespace);
	}

	/**
	 * Save a javax.jms.Message to an esbmsg file.
	 * 
	 * @param message the message to save
	 * @param file to save to
	 * @throws ParserConfigurationException
	 * @throws IOException
	 * @throws TransformerFactoryConfigurationError
	 * @throws TransformerException
	 * @throws JMSException 
	 */
	public void saveToFile(Message message, File file) throws ParserConfigurationException, IOException, TransformerFactoryConfigurationError, TransformerException, JMSException {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.newDocument();
		saveToDocument(doc, message);
		
		// Save to file
		FileOutputStream fout = new FileOutputStream(file);
						
		// Use JAXP (preferred)
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		StreamResult result = new StreamResult(fout);
		
		DOMSource source = new DOMSource(Xerces210DocumentWrapper.wrap(doc));
		transformer.transform(source, result);
		
		fout.flush();
		fout.close();
	}

	/**
	 * Serialize a {@link Message} object into an existing Document object.
	 * 
	 * @param doc
	 * @param message
	 */
	private void saveToDocument(Document doc, Message message) throws JMSException {
		Element messageElement = createMessageElement(doc);

		// Build the content of the message
		saveHeaders(messageElement, message);
		saveProperties(messageElement, message);
		saveContent(messageElement, message);
		doc.appendChild(messageElement);		
	}

	private Element createMessageElement(Document doc) {
		Element messageElement = doc.createElementNS(
				getMessageNamespace(), getQualifiedMessageElementName());
		return messageElement;
	}


	

	/**
	 * Save the contents of a {@link Message} to a DOM Element.
	 * 
	 * @param messageElement
	 * @param message
	 */
	private void saveContent(Element messageElement, Message message) throws JMSException {
		messageElement.setAttribute("type", MessageType.fromClass(message.getClass()).toString());
		
		if(JMSMultipartMessage.class.isAssignableFrom(message.getClass())) {
			JMSMultipartMessage mm = (JMSMultipartMessage)message;
			
			// Set the parts
			for(int i = 0; i<mm.getPartCount(); i++) {
				savePart(messageElement, mm.getPart(i), i);
			}
		} else {
			// Create the body part
			saveBody(messageElement, message);
		}
	}

	@SuppressWarnings("unchecked")
    private void saveBody(Element messageElement, Message message) throws JMSException {
		Element e = messageElement.getOwnerDocument().createElement("body");
		e.setAttribute("content-id", "body-part");
		e.setAttribute("file-ref", "");
		e.setAttribute("use-file-ref", "false");
		if (TextMessage.class.isAssignableFrom(message.getClass())){
			e.setTextContent(((TextMessage)message).getText());
		} else if(BytesMessage.class.isAssignableFrom(message.getClass())) {
			BytesMessage bm = (BytesMessage)message;
			bm.reset();
			byte[] bytes = new byte[(int)bm.getBodyLength()];
			bm.readBytes(bytes);
			e.setTextContent(Base64.getEncoder().encodeToString(bytes));
		//MAPStart
		} else if(MapMessage.class.isAssignableFrom(message.getClass())) {
		        MapMessage mm = (MapMessage)message;
		        Enumeration<String> mapNames = mm.getMapNames();
	                
	                while(mapNames.hasMoreElements()) {
	                        String name = mapNames.nextElement();
	                        
	                        final Object value = mm.getObject(name);
	                        if(value != null) {
	                                Element k = e.getOwnerDocument().createElement("key");
	                                k.setAttribute("name", name);
	                                k.setAttribute("value", buildValue(value));
	                                k.setAttribute("type", buildType(value));
	                                e.appendChild(k);
	                        }
	                }
		}
		//MAPEnd
		else {
			throw new RuntimeException("Unsupported message type: " + message.getClass());
		}
		messageElement.appendChild(e);
	}
	
	private String buildValue( Object obj){
	    //if object is of byte[] then base 64 encoding should be returned
	    if(obj instanceof byte[]){
	        return Base64.getEncoder().encodeToString((byte[])obj);
	    }else{
	        return obj.toString();
	    }
	}
	
	private String buildType( Object obj){
            //if object is of byte[] getClass returns [B
            if(obj instanceof byte[]){
                return "byte[]";
            }else{
                return obj.getClass().getName();
            }
        }
	
	private void savePart(Element messageElement, JMSPart part, int partIndex){
		String contentType = part.getContentType();
		Element e = messageElement.getOwnerDocument().createElement("part");
		e.setAttribute("content-type", contentType);
		e.setAttribute("content-id", "part-" + partIndex);
		e.setAttribute("file-ref", "");
		e.setAttribute("use-file-ref", "false");
		if (contentType.startsWith(CONTENT_ANYTEXT)){
			e.setTextContent((String)part.getContent());
		} else {
			e.setTextContent(Base64.getEncoder().encodeToString(part.getContentBytes()));
		}
		messageElement.appendChild(e);
	}
	
	/**
	 * Serialize the properties of a {@link Message} object into a DOM Element.
	 * 
	 * @param messageElement
	 * @param message
	 */
	@SuppressWarnings("unchecked")
	private void saveProperties(Element messageElement, Message message) throws JMSException {
		Enumeration<String> propertyNames = message.getPropertyNames();
		
		while(propertyNames.hasMoreElements()) {
			String name = propertyNames.nextElement();
			
			// Skip SonicMQ specific property for multipart messages
			if(PROPERTY_EXTENDED_TYPE.equals(name))
				continue;
			
			// Skip content type because that is already specified elsewhere in the file format.
			if(PROPERTY_CONTENT_TYPE.equals(name))
				continue;
			
			final Object value = message.getObjectProperty(name);
			if(value != null) {
				Element e = messageElement.getOwnerDocument().createElement("property");
				e.setAttribute("name", name);
				e.setAttribute("value", value.toString());
				e.setAttribute("type", value.getClass().getName());
				messageElement.appendChild(e);
			}
		}
	}

	/**
	 * Save JMSCorrelationID and JMSReplyTo if set
	 * 
	 * @param messageElement
	 * @param message
	 */
	private void saveHeaders(Element messageElement, Message message) throws JMSException {
		if(message.getJMSCorrelationID() != null && message.getJMSCorrelationID().length() > 0) {
			Element e = messageElement.getOwnerDocument().createElement("header");
			e.setAttribute("name", HEADER_JMSCORRELATIONID);
			e.setAttribute("value", message.getJMSCorrelationID());
			messageElement.appendChild(e);
		}
		
		if(message.getJMSReplyTo() != null) {
			Element e = messageElement.getOwnerDocument().createElement("header");
			e.setAttribute("name", HEADER_JMSREPLYTO);
			e.setAttribute("value", message.getJMSReplyTo().toString());
			messageElement.appendChild(e);
		}
	}
	
	/**
	 * Create a javax.jms.Message by parsing an existing file.
	 * 
	 * @param file to be parsed
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 * @throws IOException
	 * @throws XPathExpressionException
	 * @throws JMSException 
	 */
	public Message readFromFile(File file) throws ParserConfigurationException, SAXException, IOException, XPathExpressionException, JMSException {

		// Read the file and parse as XML
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(file);
		
		// Create a message from the Document
		return readFromDocument(file, doc);
	}

	/**
	 * Read an ESBMessage from the Document and return as a {@link Message}.
	 * 
	 * @param doc
	 * @return
	 * @throws XPathExpressionException
	 * @throws IOException
	 * @throws JMSException 
	 */
	private Message readFromDocument(File esbmsgFile, Document doc) throws XPathExpressionException, IOException, JMSException {
		// First find out what type of message this Document represents
		XPath xpath = getXPath();
		MessageType type = MessageType.fromString((String)xpath.evaluate(
			"/" + getQualifiedMessageElementName() + "/@type", doc.getDocumentElement(), XPathConstants.STRING));

		Message message = createMessage(type);

		// Process headers
		readHeaders(doc, message);

		// Process properties
		readProperties(doc, message);

		// Process message content
		readContent(esbmsgFile, doc, message);

		return message;
	}

	private Message createMessage(MessageType type) {
		switch(type) {
		case MESSAGE:
			return MessageFactory.createMessage();
		case TEXT_MESSAGE:
			return MessageFactory.createTextMessage();
		case XML_MESSAGE:
			return MessageFactory.createXMLMessage();
		case BYTES_MESSAGE:
			return MessageFactory.createBytesMessage();
		//MAPStart
		case MAP_MESSAGE:
                    return MessageFactory.createMapMessage();
            	//MAPEnd
		case MULTIPART_MESSAGE:
			return MessageFactory.createMultipartMessage();
		}
		
		throw new RuntimeException("Unknown message type: " + type);
	}
	
	/**
	 * Read the content of an ESBMessage from the Document and store in the {@link Message}.
	 * 
	 * @param doc
	 * @param message
	 * @throws XPathExpressionException
	 * @throws IOException
	 * @throws JMSException 
	 */
	private void readContent(File esbmsgFile, Document doc, Message message) throws XPathExpressionException, IOException, JMSException {
		switch(MessageType.fromClass(message.getClass())) {
		case MESSAGE:
			// There is no content in an untyped Message
			break;
			
		case TEXT_MESSAGE:
		case XML_MESSAGE:
			readTextBody(esbmsgFile, doc, (TextMessage)message);
			break;
			
		case BYTES_MESSAGE:
			readBytesBody(esbmsgFile, doc, (BytesMessage)message);
			break;
		//MAPStart
		case MAP_MESSAGE:
                    readMapBody(esbmsgFile, doc, (MapMessage)message);
                    break;
		//MAPEnd	
		case MULTIPART_MESSAGE:
			readMultipartBody(esbmsgFile, doc, (JMSMultipartMessage)message);
		}
	}
	//MAPStart
	/**
         * Read the the Map content of the message from the Document.
         * 
         * @param doc
         * @param message
         * @throws JMSException
         * @throws XPathExpressionException
         * @throws IOException
         */
        private void readMapBody(File esbmsgFile, Document doc, MapMessage message)
            throws JMSException, XPathExpressionException, IOException {
            XPath xpath = getXPath();
            NodeList propertyNodes = (NodeList) xpath.evaluate("/" + getQualifiedMessageElementName() + "/body/key",
                doc, XPathConstants.NODESET);
            for (int i = 0; i < propertyNodes.getLength(); i++) {
                Node propertyNode = propertyNodes.item(i);
    
                String propertyName = getAttributeValue(propertyNode, "name");
                String propertyValue = getAttributeValue(propertyNode, "value");
                String propertyType = getAttributeValue(propertyNode, "type");
    
                if (propertyName != null && propertyName.length() > 0)
                    message.setObject(propertyName, createPropertyObject(propertyType, propertyValue));
            }
        }
	//MAPEnd
	/**
	 * Read the the text content of the message from the Document.
	 * 
	 * @param doc
	 * @param message
	 * @throws JMSException
	 * @throws XPathExpressionException
	 * @throws IOException
	 */
	private void readTextBody(File esbmsgFile, Document doc, TextMessage message) throws JMSException, XPathExpressionException, IOException {
		XPath xpath = getXPath();
		Node bodyNode = (Node) xpath.evaluate(
				"/" + getQualifiedMessageElementName() + "/body", doc, XPathConstants.NODE);
		
		if(bodyNode == null)
			return;
		
		String fileRef = getAttributeValue(bodyNode, "file-ref");
		boolean useFileRef = Boolean.parseBoolean(getAttributeValue(bodyNode, "use-file-ref"));
		
		if(useFileRef) {
			message.setText(new String(resolveFileRef(esbmsgFile, fileRef)));
		} else {
			message.setText(bodyNode.getTextContent());
		}
	}

	/**
	 * Read the bytes content of the message from the Document (base64 encoded) or from the referenced files (as-is).
	 * 
	 * @param doc
	 * @param message
	 * @throws JMSException
	 * @throws XPathExpressionException
	 * @throws IOException
	 */
	private void readBytesBody(File esbmsgFile, Document doc, BytesMessage message) throws JMSException, XPathExpressionException, IOException {
		XPath xpath = getXPath();
		Node bodyNode = (Node) xpath.evaluate(
				"/" + getQualifiedMessageElementName() + "/body", doc, XPathConstants.NODE);
		
		if(bodyNode == null)
			return;
		
		String fileRef = getAttributeValue(bodyNode, "file-ref");
		boolean useFileRef = Boolean.parseBoolean(getAttributeValue(bodyNode, "use-file-ref"));
		
		if(useFileRef) {
			message.writeBytes(resolveFileRef(esbmsgFile, fileRef));
		} else {
			message.writeBytes(Base64.getDecoder().decode(bodyNode.getTextContent()));
		}
		
		// Put the message in read-only mode and set the read pointer to 0
		message.reset();
	}

	/**
	 * Read a multi-part ESBMessage from the Document and store in {@link Message}.
	 * 
	 * @param doc
	 * @param message
	 * @throws XPathExpressionException
	 * @throws IOException
	 * @throws JMSException 
	 */
	private void readMultipartBody(File esbmsgFile, Document doc, JMSMultipartMessage message) throws XPathExpressionException, IOException, JMSException {
		XPath xpath = getXPath();
		NodeList partNodes = (NodeList) xpath.evaluate(
				"/" + getQualifiedMessageElementName() + "/part", doc, XPathConstants.NODESET);
		
		int numberOfParts = partNodes.getLength();
		contentIds= new String[numberOfParts];
		
		for(int i=0; i<numberOfParts; i++) {
			Node partNode = partNodes.item(i);
			
			String contentType = getAttributeValue(partNode, "content-type");
			String fileRef = getAttributeValue(partNode, "file-ref");
			
			String contentID =  getAttributeValue(partNode, "content-id");
			
			boolean useFileRef = Boolean.parseBoolean(getAttributeValue(partNode, "use-file-ref"));
			JMSPart messagePart;
			if(useFileRef) {
				if (contentType.startsWith(CONTENT_ANYTEXT)) {
					message.addPart(messagePart=message.createPart(new String(resolveFileRef(esbmsgFile, fileRef)), contentType));
				} else {
					message.addPart(messagePart=message.createPart(resolveFileRef(esbmsgFile, fileRef), contentType));
				}
			} else {
				if (contentType.startsWith(CONTENT_ANYTEXT)) {
					message.addPart(messagePart=message.createPart(partNode.getTextContent(), contentType));
				} else {
					message.addPart(messagePart=message.createPart(
							Base64.getDecoder().decode(partNode.getTextContent()), contentType));
					
				}
			}
			messagePart.setHeaderField("Content-ID", contentID);
		}
	}

	private void readProperties(Document doc, Message message) throws XPathExpressionException, JMSException {
		XPath xpath = getXPath();
		NodeList propertyNodes = (NodeList) xpath.evaluate(
				"/" + getQualifiedMessageElementName() + "/property", doc, XPathConstants.NODESET);
		
		for(int i=0; i<propertyNodes.getLength(); i++) {
			Node propertyNode = propertyNodes.item(i);
			
			String propertyName = getAttributeValue(propertyNode, "name");
			String propertyValue = getAttributeValue(propertyNode, "value");
			String propertyType = getAttributeValue(propertyNode, "type");

			if(PROPERTY_EXTENDED_TYPE.equals(propertyName))
				continue;
			
			if(PROPERTY_CONTENT_TYPE.equals(propertyName))
				continue;
			
			if(propertyName != null && propertyName.length() > 0)
				message.setObjectProperty(propertyName, createPropertyObject(propertyType, propertyValue));
		}
	}

	/**
	 * Creates an Property object of the corresponding type
	 * 
	 * */
	private Object createPropertyObject(String type, String value){
		if(type == null || "".equals(type))
			return value;
		
		if (type.equalsIgnoreCase(Boolean.class.getName()))
			return Boolean.parseBoolean(value);
		if (type.equalsIgnoreCase(Byte.class.getName()))
			return Byte.parseByte(value);
		if (type.equalsIgnoreCase(Short.class.getName()))
			return Short.parseShort(value);
		if (type.equalsIgnoreCase(Integer.class.getName()))
			return Integer.parseInt(value);
		if (type.equalsIgnoreCase(Long.class.getName()))
			return Long.parseLong(value);
		if (type.equalsIgnoreCase(Float.class.getName()))
			return Float.parseFloat(value);
		if (type.equalsIgnoreCase(Double.class.getName()))
			return Double.parseDouble(value);
		if (type.equalsIgnoreCase("byte[]"))
		        return Base64.getDecoder().decode(value);
		return value;
	}
	
	private void readHeaders(Document doc, Message message) throws XPathExpressionException, JMSException {
		XPath xpath = getXPath();
		NodeList headerNodes = (NodeList) xpath.evaluate(
			"/" + getQualifiedMessageElementName() + "/header", doc, XPathConstants.NODESET);
		
		for(int i=0; i<headerNodes.getLength(); i++) {
			Node headerNode = headerNodes.item(i);
			
			String headerName = getAttributeValue(headerNode, "name");
			String headerValue = getAttributeValue(headerNode, "value");
			
			if(HEADER_JMSCORRELATIONID.equals(headerName)) {
				message.setJMSCorrelationID(headerValue);
//			FIXME Need to find a way to extract the reply to as a JMSDestination object
//			} else if(HEADER_JMSREPLYTO.equals(headerName)) {
//				message.setReplyTo(headerValue);
			} else {
				System.err.println("Header name " + headerName + " not supported. Ignored.");
			}
		}
	}
	
	/**
	 * Read the contents of the referenced file
	 * 
	 * @param fileRef
	 * @return
	 * @throws IOException
	 */
	private byte[] resolveFileRef(File esbmsgFile, String fileRef) throws IOException {
		File file = null;
		
		// Try to resolve a sonicfs:// reference first
		if(fileRef.startsWith("sonicfs")) {
			file = new File(resolveSonicFSFileRef(esbmsgFile, fileRef));
			if(file.canRead())
				return readFile(file);
		}

		// Now try to treat the fileRef as an absolute path
		file = new File(fileRef);
		if(file.canRead())
			return readFile(file);

		// Try to read the file relative to the esbmsgFile itself
		file = new File(esbmsgFile.getParentFile(), fileRef);
		if(file.canRead())
			return readFile(file);

		// Unable to resolve the ref. Give up.
		throw new RuntimeException("Unable to resolve file reference " + fileRef);
	}
	
	private byte[] readFile(File file) throws IOException {
		byte[] buffer = new byte[(int)file.length()];
		InputStream is = new FileInputStream(file);
		is.read(buffer);
		is.close();

		return buffer;
	}
	
	/**
	 * Attempt to discover the location of the fileref relative to the esbmsg file
	 * 
	 * @param fileRef
	 * @return
	 * @throws IOException 
	 */
	private String resolveSonicFSFileRef(File esbmsgFile, String fileRef) throws IOException {
		// Walk through the fileRef string, removing the first character each iteration
		// when a match is found in the esbmsgFile, the characters before the index that
		// matched are replaced in front of the remaining fileRef to make it absolute.
		String esbmsgPath = esbmsgFile.getParentFile().getAbsolutePath().replace('\\', '/');
		
		// First remove file name from the fileRef and normalize slashes
		fileRef = fileRef.replaceAll("//", "/");
		String fileRefFilename = fileRef.substring(fileRef.lastIndexOf('/')+1);
		String fileRefPath = fileRef.substring(0, fileRef.lastIndexOf('/')+1);
		
		String absoluteFileRef = null;
		
		// Find a match if there is one
		while(fileRefPath.length()>0) {
			int index = -1;
			if((index = findMatchIndex(fileRefPath, esbmsgPath)) != -1) {
				// Found a match
				absoluteFileRef = 
					esbmsgPath.substring(0, index) + fileRefPath + fileRefFilename;
				break;
			} else {
				fileRefPath = fileRefPath.substring(fileRefPath.indexOf('/')+1);
			}
		}
		
		// If there is no match in pathnames, find the referenced file in the esbmsg directory
		if(absoluteFileRef == null || !new File(absoluteFileRef).exists())
			absoluteFileRef = esbmsgPath + '/' + fileRefFilename;
		
		return absoluteFileRef;
	}

	/**
	 * Try to find the index of needle in haystack by removing pathelements from
	 * needle and matching against haystack
	 * 
	 * @param needle
	 * @param haystack
	 * @return
	 */
	private int findMatchIndex(String needle, String haystack) {
//		System.out.println("findMatchIndex(" + needle + ", " + haystack + ")");
		
		int index = -1;
		while((index = haystack.indexOf(needle)) == -1) {
//			System.out.println("Needle: " + needle + ", Haystack: " + haystack);
			if(needle.lastIndexOf('/')>=0)
				needle = needle.substring(0, needle.lastIndexOf('/'));
			else
				break;
//			System.out.println("Needle: " + needle + ", Haystack: " + haystack);
		}
		
		return index;
	}

	private String getAttributeValue(Node node, String attribute) {
		NamedNodeMap map = node.getAttributes();
		Node attributeNode = map.getNamedItem(attribute);
		
		if(attributeNode == null)
			return null;
		
		return attributeNode.getTextContent();
	}
	
	private XPath getXPath() {
		XPath xpath = xpathFactory.newXPath();
		xpath.setNamespaceContext(namespaceContext);
		return xpath;
	}
}