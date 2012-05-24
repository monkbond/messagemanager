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
package nl.queuemanager.smm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import nl.queuemanager.core.MapNamespaceContext;
import nl.queuemanager.core.util.CollectionFactory;
import nl.queuemanager.core.util.Credentials;
import nl.queuemanager.jms.JMSBroker;
import nl.queuemanager.jms.JMSTopic;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Preferences utility class. Handles the format and location of the preferences,
 * no details about the preferences implementation should be outside of this class!
 *
 * FIXME The XML handling in here is terrible!
 * 
 * @author Gerco Dries (gdr@progaia-rs.nl)
 *
 */
class XmlConfiguration implements SMMConfiguration {
	private static final String ROOT_ELEMENT = "Configuration";

	private static final String NAMESPACE_URI = "urn:SonicMessageManagerConfig";
	
	private final DocumentBuilderFactory dbf;
	private final DocumentBuilder db;
	
	private final TransformerFactory tff;
	private final Transformer tf;
	
	private final XPathFactory xpf;
	private final XPath xp;
	
	private static final File prefsFile = 
		new File(System.getProperty("user.home"), ".SonicMessageManager.xml");
		
	XmlConfiguration() {
		// Initialize the XML Parser
		dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		try {
			db = dbf.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			throw new RuntimeException("Unable to configure XML parser!");
		}	
		
		// Initialize the XML generator
		tff = TransformerFactory.newInstance();
		try {
			tf = tff.newTransformer();
		} catch (TransformerConfigurationException e) {
			throw new RuntimeException("Unable to configure XML generator!");
		}
		
		// Initialize the XPath processor
		xpf = XPathFactory.newInstance();
		xp = xpf.newXPath();
		MapNamespaceContext nsContext = new MapNamespaceContext();
		nsContext.add("c", NAMESPACE_URI);
		xp.setNamespaceContext(nsContext);
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.core.ConfigurationManager#getUserPref(java.lang.String, java.lang.String)
	 */
	public synchronized String getUserPref(String key, String def) {
		Document prefs = readPrefs();
		String result = "";
		
		try {
			result = xp.evaluate(String.format("/c:%s/c:%s", ROOT_ELEMENT, key), prefs.getDocumentElement());
		} catch (XPathExpressionException e) {
			System.err.println("Unable to get preference key " + key + ":");
			e.printStackTrace();
			return def;
		}
		
		if(result != null && result.length()>0)
			return result;

		// No value for this preference, save the default value
		if(def != null && !"".equals(def))
			setUserPref(key, def);
		return def;
	}
		
	/* (non-Javadoc)
	 * @see nl.queuemanager.core.ConfigurationManager#setUserPref(java.lang.String, java.lang.String)
	 */
	public synchronized void setUserPref(String key, String value) {
		Document prefs = readPrefs();
		
		try {
			setElementValue(prefs.getDocumentElement(), new String[]{key}, value);
			savePrefs(prefs);
		} catch (XPathExpressionException e) {
			e.printStackTrace();
		}
	}

	public synchronized List<JMSBroker> listBrokers() {
		Document prefs = readPrefs();
		List<JMSBroker> brokers = new ArrayList<JMSBroker>();
		
		String expr = String.format("/c:%s/c:Broker", ROOT_ELEMENT);
		try {
			NodeList brokerNodes = (NodeList)xp.evaluate(expr, prefs.getDocumentElement(), XPathConstants.NODESET);
			for(int i=0; i< brokerNodes.getLength(); i++) {
				Element brokerElement = (Element) brokerNodes.item(i);
				brokers.add(new JMSBrokerName(brokerElement.getAttribute("name")));
			}
		} catch (XPathExpressionException e) {
			e.printStackTrace();
		}
		return brokers;
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.core.ConfigurationManager#getBrokerPref(nl.queuemanager.core.jms.JMSBroker, java.lang.String, java.lang.String)
	 */
	public synchronized String getBrokerPref(JMSBroker broker, String key, String def) {
		Document prefs = readPrefs();
		
		String expr = String.format("/c:%s/c:Broker[@name='%s']/c:%s", 
				ROOT_ELEMENT, broker.toString(), key);
		try {
			String res = (String)xp.evaluate(expr, prefs.getDocumentElement(), XPathConstants.STRING);
			if(res != null && !"".equals(res))
				return res;
		} catch (XPathExpressionException e) {
			System.out.println(String.format(
					"Unable to retrieve key %s for broker %s", key, broker.toString()));
			e.printStackTrace();
		}

		// No value foud for this pref, save the default value
		if(def != null && !"".equals(def))
			setBrokerPref(broker, key, def);
		return def;
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.core.ConfigurationManager#setBrokerPref(nl.queuemanager.core.jms.JMSBroker, java.lang.String, java.lang.String)
	 */
	public synchronized void setBrokerPref(JMSBroker broker, String key, String value) {
		Document prefs = readPrefs();
		
		try {
			setElementValue(getBrokerElement(prefs, broker.toString()), new String[]{key}, value);
			savePrefs(prefs);
		} catch (XPathExpressionException e) {
			e.printStackTrace();
		}
	}
	
	public void setBrokerCredentials(JMSBroker broker, Credentials credentials) {
		Document prefs = readPrefs();
		try {
			Element brokerElement = getBrokerElement(prefs, broker.toString());
			setElementValue(brokerElement, new String[] { "DefaultUsername" }, credentials.getUsername());
			setElementValue(brokerElement, new String[] { "DefaultPassword" }, credentials.getPassword());
			savePrefs(prefs);
		} catch (XPathExpressionException e) {
			e.printStackTrace();
		}
	}

	public Credentials getBrokerCredentials(JMSBroker broker) {
		String username = getBrokerPref(broker, "DefaultUsername", null);
		String password = getBrokerPref(broker, "DefaultPassword", null);
		if (username != null && password != null)
			return new Credentials(username, password);
		return null;
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.core.ConfigurationManager#getTopicSubscriberNames(nl.queuemanager.core.jms.JMSBroker)
	 */
	public synchronized List<String> getTopicSubscriberNames(JMSBroker broker) {
		Document prefs = readPrefs();
		
		String expr = String.format("/c:%s/c:Broker[@name='%s']/c:Subscribers/c:Subscriber", 
				ROOT_ELEMENT, broker.toString());
		try {
			return getNodeValues(prefs.getDocumentElement(), expr);
		} catch (XPathExpressionException e) {
			System.out.println("Unable to retrieve topic subscriber names for broker " + broker);
			e.printStackTrace();
		}
		
		return CollectionFactory.newArrayList();
	}

	/* (non-Javadoc)
	 * @see nl.queuemanager.core.ConfigurationManager#getTopicPublisherNames(nl.queuemanager.core.jms.JMSBroker)
	 */
	public synchronized List<String> getTopicPublisherNames(JMSBroker broker) {
		Document prefs = readPrefs();
		
		String expr = String.format("/c:%s/c:Broker[@name='%s']/c:Publishers/c:Publisher", 
				ROOT_ELEMENT, broker.toString());
		try {
			return getNodeValues(prefs.getDocumentElement(), expr);
		} catch (XPathExpressionException e) {
			System.out.println("Unable to retrieve topic publisher names for broker " + broker);
			e.printStackTrace();
		}
		
		return CollectionFactory.newArrayList();
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.core.ConfigurationManager#addTopicSubscriber(nl.queuemanager.core.jms.JMSTopic)
	 */
	public synchronized void addTopicSubscriber(JMSTopic topic) {
		if(getTopicSubscriberNames(topic.getBroker()).contains(topic.getName()))
			return;
		
		Document prefs = readPrefs();
		
		try {
			Node brokerElement = getBrokerElement(prefs, topic.getBroker().toString()); 
			Node subscribersElement = getOrCreateElementAtPath(brokerElement, new String[] {"Subscribers"});
			addElement(subscribersElement, "Subscriber", topic.getName());
			
			savePrefs(prefs);
		} catch (XPathExpressionException e) {
			e.printStackTrace();
		}
	}

	/* (non-Javadoc)
	 * @see nl.queuemanager.core.ConfigurationManager#addTopicPublisher(nl.queuemanager.core.jms.JMSTopic)
	 */
	public synchronized void addTopicPublisher(JMSTopic topic) {
		if(getTopicPublisherNames(topic.getBroker()).contains(topic.getName()))
			return;
		
		Document prefs = readPrefs();
		
		try {
			Node brokerElement = getBrokerElement(prefs, topic.getBroker().toString()); 
			Node subscribersElement = getOrCreateElementAtPath(brokerElement, new String[] {"Publishers"});
			addElement(subscribersElement, "Publisher", topic.getName());
			
			savePrefs(prefs);
		} catch (XPathExpressionException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Remove a topic subscriber from the saved list for its associated broker.
	 * 
	 * @param topic
	 */
	public synchronized void removeTopicSubscriber(JMSTopic topic) {
		removePrefNode(String.format(
			"/c:%s/c:Broker[@name='%s']/c:Subscribers/c:Subscriber[text()='%s']", 
			ROOT_ELEMENT, topic.getBroker().toString(), topic.getName()));
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.core.ConfigurationManager#removeTopicPublisher(nl.queuemanager.core.jms.JMSTopic)
	 */
	public synchronized void removeTopicPublisher(JMSTopic topic) {
		removePrefNode(String.format(
			"/c:%s/c:Broker[@name='%s']/c:Publishers/c:Publisher[text()='%s']", 
			ROOT_ELEMENT, topic.getBroker().toString(), topic.getName()));
	}

	/**
	 * Return the values of the matched nodes as a list of strings.
	 * 
	 * @param context
	 * @param xpathExpression
	 * @return
	 * @throws XPathExpressionException 
	 */
	private List<String> getNodeValues(Node context, String xpathExpression) throws XPathExpressionException {
		List<String> result = CollectionFactory.newArrayList();
		NodeList resultNodes = (NodeList)xp.evaluate(xpathExpression, context, XPathConstants.NODESET);
		if(resultNodes != null) {
			for(int i = 0; i<resultNodes.getLength(); i++)
				result.add(resultNodes.item(i).getTextContent());
		}
		return result;
	}

	/**
	 * Set the value of the Element indicated by the path, creating Elements if required.
	 * 
	 * @param path
	 * @param value
	 * @throws XPathExpressionException 
	 */
	private void setElementValue(final Node context, final String[] path, final String value) throws XPathExpressionException {
		Node node = getOrCreateElementAtPath(context, path);
		node.setTextContent(value);
	}

	/**
	 * Get or create the Elements at the specified path.
	 * 
	 * @param context
	 * @param path
	 * @return The last element of the path
	 * @throws XPathExpressionException 
	 */
	private Node getOrCreateElementAtPath(final Node context, final String[] path) throws XPathExpressionException {
		Node curNode = context;
		
		for(String cur: path) {
			Node potential = (Node)xp.evaluate("c:" + cur, curNode, XPathConstants.NODE);
			if(potential == null) {
				// Create the node and add to the document. Then use as the current node.
				potential = curNode.getOwnerDocument().createElementNS(NAMESPACE_URI, cur);
				curNode.appendChild(potential);
			}
			curNode = potential;
		}
		
		return curNode;
	}
	
	/**
	 * Add an element to the context node with the specified value.
	 * 
	 * @param context
	 * @param nodeName
	 * @param nodeValue
	 */
	private void addElement(final Node context, final String nodeName, final String nodeValue) {
		Node newNode = context.getOwnerDocument().createElementNS(NAMESPACE_URI, nodeName);
		newNode.setTextContent(nodeValue);
		context.appendChild(newNode);
	}
	
	/**
	 * Remove a single node from the preferences document if it exists.
	 * 
	 * @param xpathExpression
	 */
	private void removePrefNode(String xpathExpression) {
		Document prefs = readPrefs();

		try {
			Node nodeToRemove = (Node)xp.evaluate(
					xpathExpression, prefs.getDocumentElement(), XPathConstants.NODE);
			if(nodeToRemove != null) {
				nodeToRemove.getParentNode().removeChild(nodeToRemove);
				savePrefs(prefs);
			}
		} catch (XPathExpressionException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Get the Element that refers to a certain broker. Creating it if it doesn't
	 * exist yet.
	 *  
	 * @param prefs
	 * @param brokerName
	 * @return
	 * @throws XPathExpressionException
	 */
	private Element getBrokerElement(Document prefs, String brokerName)
			throws XPathExpressionException {
		Element brokerElement = (Element)xp.evaluate(
				String.format("/c:%s/c:Broker[@name='%s']", ROOT_ELEMENT, brokerName),
				prefs.getDocumentElement(), XPathConstants.NODE);
		if(brokerElement == null) {
			brokerElement = prefs.createElementNS(NAMESPACE_URI, "Broker");
			Attr nameAttribute = prefs.createAttribute("name");
			nameAttribute.setTextContent(brokerName);
			brokerElement.getAttributes().setNamedItem(nameAttribute);
			prefs.getDocumentElement().appendChild(brokerElement);
		}
		
		return brokerElement;
	}
	
	/**
	 * Read the existing preference document or create a new one if it doesn't exist yet.
	 * 
	 * @return
	 */
	private Document readPrefs() {		
		if(!prefsFile.exists()) {
			// The file does not exist. Create a new Document.
			return newConfig();
		}
		
		try {
			return db.parse(prefsFile);
		} catch (IOException e) {
			System.out.println("IOException getting configuration, creating new document." + e);
			e.printStackTrace();
			return newConfig();
		} catch (SAXException e) {
			System.out.println("Unable to parse configuration, creating new document." + e);
			e.printStackTrace();
			return newConfig();
		}
	}

	/**
	 * Save the Document object to the preferences file.
	 * 
	 * @param doc
	 */
	private void savePrefs(Document doc) {
		try {
			StreamResult r = new StreamResult(prefsFile);
			Source s = new DOMSource(doc);
			
			tf.transform(s, r);
		} catch (TransformerException e) {
			System.err.println("Error while saving prefs!");
			e.printStackTrace(System.err);
		}
	}
	
	/**
	 * Create a new, empty, configuration document.
	 * 
	 * @return
	 */
	private Document newConfig() {
		Document d = db.newDocument();
		Element configElement = d.createElementNS(NAMESPACE_URI, ROOT_ELEMENT);
		d.appendChild(configElement);
		
		// Read possibly existing settings from the java.util.Preferences store and copy them to
		// the configuration document. Then remove them.
		convertOldUserPref(configElement, PREF_BROWSE_DIRECTORY);
		convertOldUserPref(configElement, PREF_SAVE_DIRECTORY);
		convertOldUserPref(configElement, PREF_MAILINGLIST_STATUS);

		savePrefs(d);
		return d;
	}

	/**
	 * Convert an old preference to the new preferences format and remove the old preference.
	 * 
	 * @param configElement
	 * @param key
	 */
	private void convertOldUserPref(final Element configElement, final String key) {
		final String browseDir = getOldUserPref(key);
		if(browseDir != null) {
			Element e = configElement.getOwnerDocument().createElementNS(NAMESPACE_URI, key);
			e.setTextContent(browseDir);
			configElement.appendChild(e);
			removeOldUserPref(key);
		}
	}

	/**
	 * Remove a preference from the java.util.Preferences store.
	 * 
	 * @param key
	 */
	private void removeOldUserPref(final String key) {
		Preferences userNode = Preferences.userNodeForPackage(XmlConfiguration.class);
		userNode.remove(key);
	}

	/**
	 * Get a preference value from the java.util.Preferences store.
	 * 
	 * @param key
	 * @return
	 */
	private String getOldUserPref(final String key) {
		Preferences userNode = Preferences.userNodeForPackage(XmlConfiguration.class);
		return userNode.get(key, null);
	}
	
	private class JMSBrokerName implements JMSBroker {
		private final String name;

		public JMSBrokerName(String name) {
			this.name = name;
		}
		
		public String toString() {
			return name;
		}
		
		public int compareTo(JMSBroker other) {
			return name.compareTo(other.toString());
		}
		
	}
}
