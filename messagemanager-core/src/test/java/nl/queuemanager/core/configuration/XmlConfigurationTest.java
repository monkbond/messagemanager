package nl.queuemanager.core.configuration;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.List;

import nl.queuemanager.core.configuration.XmlConfiguration.JMSBrokerName;
import nl.queuemanager.core.util.Credentials;
import nl.queuemanager.jms.JMSBroker;
import nl.queuemanager.jms.impl.DestinationFactory;

import org.junit.Before;
import org.junit.Test;

public class XmlConfigurationTest {
	
	private static final String NAMESPACE_URI = "urn:test-config"; 
	private XmlConfiguration config;
	private File configFile;
	
	@Before
	public void before() throws IOException {
		configFile = File.createTempFile("mmtest", "xml");
		configFile.deleteOnExit();
		config = new XmlConfiguration(configFile, NAMESPACE_URI);
	}
	
	@Test
	public void testGetUniqueId() {
		String uuid = config.getUniqueId();
		assertNotNull(uuid);
		assertEquals(36, uuid.length());
	}

	@Test
	public void testGetSetUserPref() {
		final String KEY = "someKey";
		final String VALUE = "some value";
		final String DEF = "default value";

		// Check that the default works
		assertEquals(DEF, config.getUserPref(KEY, DEF));
		
		// Now set a value and check the new value
		config.setUserPref(KEY, VALUE);
		assertEquals(VALUE, config.getUserPref(KEY, "default value"));
	}

	@Test
	public void testListBrokers() {
		// Initially, empty list
		assertTrue(config.listBrokers().isEmpty());
		
		// Add a broker and retest
		config.setBrokerPref(new JMSBrokerName("broker1"), "key", "value");
		assertEquals(1, config.listBrokers().size());
		
		// Add another broker and retest
		config.setBrokerPref(new JMSBrokerName("broker2"), "key", "value");
		assertEquals(2, config.listBrokers().size());
		
		// Set another property on an existing broker and retest
		config.setBrokerPref(new JMSBrokerName("broker2"), "key2", "value");
		assertEquals(2, config.listBrokers().size());
	}

	@Test
	public void testGetSetBrokerPref() {
		final String KEY = "someKey";
		final String VALUE = "some value";
		final String DEF = "default value";
		final JMSBroker broker = new JMSBrokerName("some broker");

		// Test default value
		assertEquals(DEF, config.getBrokerPref(broker, KEY, DEF));
		
		// Now set a value and retrieve it
		config.setBrokerPref(broker, KEY, VALUE);
		assertEquals(VALUE, config.getBrokerPref(broker, KEY, DEF));
	}

	@Test
	public void testGetSetBrokerCredentials() {
		final JMSBroker broker = new JMSBrokerName("some broker");
		final Credentials creds = new Credentials("username", "password");
		config.setBrokerCredentials(broker, creds);
		
		final Credentials creds2 = config.getBrokerCredentials(broker);
		assertEquals(creds.getUsername(), creds2.getUsername());
		assertEquals(creds.getPassword(), creds2.getPassword());
		
		final Credentials creds3 = new Credentials("user2", "pass2");
		config.setBrokerCredentials(broker, creds3);
		
		final Credentials creds4 = config.getBrokerCredentials(broker);
		assertEquals(creds3.getUsername(), creds4.getUsername());
		assertEquals(creds3.getPassword(), creds4.getPassword());
	}

	@Test
	public void testGetTopicSubscriberNames() {
		final JMSBroker broker1 = new JMSBrokerName("broker1");
		config.addTopicSubscriber(DestinationFactory.createTopic(broker1, "topic1"));
		config.addTopicSubscriber(DestinationFactory.createTopic(broker1, "topic2"));
		config.addTopicSubscriber(DestinationFactory.createTopic(broker1, "topic3"));
		
		final JMSBroker broker2 = new JMSBrokerName("broker2");
		config.addTopicSubscriber(DestinationFactory.createTopic(broker2, "topic4"));
		config.addTopicSubscriber(DestinationFactory.createTopic(broker2, "topic5"));
		config.addTopicSubscriber(DestinationFactory.createTopic(broker2, "topic6"));
		
		List<String> names1 = config.getTopicSubscriberNames(broker1);
		assertEquals(3, names1.size());
		assertTrue(names1.contains("topic1"));
		assertTrue(names1.contains("topic2"));
		assertTrue(names1.contains("topic3"));
		
		List<String> names2 = config.getTopicSubscriberNames(broker2);
		assertEquals(3, names2.size());
		assertTrue(names2.contains("topic4"));
		assertTrue(names2.contains("topic5"));
		assertTrue(names2.contains("topic6"));
	}

	@Test
	public void testGetTopicPublisherNames() {
		final JMSBroker broker1 = new JMSBrokerName("broker1");
		config.addTopicPublisher(DestinationFactory.createTopic(broker1, "topic1"));
		config.addTopicPublisher(DestinationFactory.createTopic(broker1, "topic2"));
		config.addTopicPublisher(DestinationFactory.createTopic(broker1, "topic3"));
		
		final JMSBroker broker2 = new JMSBrokerName("broker2");
		config.addTopicPublisher(DestinationFactory.createTopic(broker2, "topic4"));
		config.addTopicPublisher(DestinationFactory.createTopic(broker2, "topic5"));
		config.addTopicPublisher(DestinationFactory.createTopic(broker2, "topic6"));

		assertContainsAll(config.getTopicPublisherNames(broker1), "topic1", "topic2", "topic3");
		config.removeTopicPublisher(DestinationFactory.createTopic(broker1, "topic2"));
		assertContainsAll(config.getTopicPublisherNames(broker1), "topic1", "topic3");

		assertContainsAll(config.getTopicPublisherNames(broker2), "topic4", "topic5", "topic6");
		config.removeTopicPublisher(DestinationFactory.createTopic(broker2, "topic6"));
		assertContainsAll(config.getTopicPublisherNames(broker2), "topic4", "topic5");
	}
	
	private static void assertContainsAll(List<String> list, String... values) {
		assertEquals(values.length, list.size());
		for(String value: values) {
			assertTrue(list.contains(value));
		}
	}

}
