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

import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sonicsw.ma.mgmtapi.config.IMgmtBeanBase;
import com.sonicsw.ma.mgmtapi.config.MgmtException;
import com.sonicsw.mf.common.IDirectoryFileSystemService;
import com.sonicsw.mf.common.config.IElementIdentity;
import com.sonicsw.mf.common.dirconfig.DirectoryServiceException;
import com.sonicsw.mf.common.runtime.IComponentState;
import com.sonicsw.mf.common.runtime.IContainerState;
import com.sonicsw.mf.common.runtime.IIdentity;
import com.sonicsw.mf.mgmtapi.runtime.IAgentManagerProxy;
import com.sonicsw.mq.common.runtime.IQueueData;
import com.sonicsw.mq.common.runtime.ReplicationStateConstants;
import com.sonicsw.mq.mgmtapi.config.*;
import com.sonicsw.mq.mgmtapi.config.IAcceptorsBean.IAcceptorMapType;
import com.sonicsw.mq.mgmtapi.config.IAcceptorsBean.IDefaultAcceptorsType;
import com.sonicsw.mq.mgmtapi.config.constants.IBackupBrokerConstants;
import com.sonicsw.mq.mgmtapi.config.constants.IBrokerConstants;
import nl.queuemanager.core.configuration.CoreConfiguration;
import nl.queuemanager.core.jms.BrokerDestinations;
import nl.queuemanager.core.jms.DomainEvent;
import nl.queuemanager.core.jms.DomainEvent.EVENT;
import nl.queuemanager.core.jms.JMSDomain;
import nl.queuemanager.core.jms.JMSFeature;
import nl.queuemanager.core.util.BasicCredentials;
import nl.queuemanager.core.util.CollectionFactory;
import nl.queuemanager.core.util.Credentials;
import nl.queuemanager.jms.JMSBroker;
import nl.queuemanager.jms.JMSDestination;
import nl.queuemanager.jms.JMSQueue;
import nl.queuemanager.jms.JMSTopic;
import nl.queuemanager.ui.BrokerCredentialsDialog;
import progress.message.jclient.MultipartMessage;
import progress.message.jclient.XMLMessage;

import jakarta.inject.Provider;
import javax.jms.*;
import javax.management.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Singleton
public class Domain implements JMSDomain {
	private final CoreConfiguration config;
	private final EventBus eventBus;
	private       ConnectionModel model;
	private final ArrayList<SonicMQBroker> brokerList = CollectionFactory.newArrayList();
	
	private Map<SonicMQBroker, SonicMQConnection> brokerConnections;
	private final Provider<BrokerCredentialsDialog> credentialsDialogProvider;
	
	{
		// Version 2.1 == Sonic MQ 6.1
		// Version 3.0 == Sonic MQ 7.0
		// SonicMQ 7.5 and onwards report their marketing version number
		System.out.println(String.format("Sonic version: %d.%d", 
				com.sonicsw.mf.common.Version.getMajorVersion(),
				com.sonicsw.mf.common.Version.getMinorVersion()));
	}

	@Inject
	public Domain(CoreConfiguration configuration, EventBus eventBus, Provider<BrokerCredentialsDialog> credentialsDialogProvider) {
		this.config = configuration;
		this.eventBus = eventBus;
		this.credentialsDialogProvider = credentialsDialogProvider;
	}
		
	public boolean isFeatureSupported(JMSFeature feature) {
		switch(feature) {
			case JMS_HEADERS:
			case TOPIC_SUBSCRIBER_CREATION:
			case FORWARD_MESSAGE:
			case QUEUE_CLEAR_MESSAGES:
			case QUEUE_DELETE_MESSAGES:
			case MESSAGE_SET_PRIORITY:
			case DESTINATION_TYPE_QUEUE:
			case DESTINATION_TYPE_TOPIC:
				return true;

			case QUEUE_MESSAGES_SIZE:
				return com.sonicsw.mf.common.Version.getMajorVersion() >= 7;

			default:
				return false;
			}
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#connect(nl.queuemanager.smm.sonic.ConnectionModel)
	 */
	public void connect(ConnectionModel model) throws Exception {
		if(this.model != null)
			disconnect();
		
		this.model = model;		
		this.brokerConnections = CollectionFactory.newHashMap();
		
		connectJMX();
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#disconnect()
	 */
	public void disconnect() throws Exception {
		disconnectJMS();
		disconnectJMX();
		this.brokerConnections = null;
	}

	/**
	 * <p>
	 * Gets a connected JMS/JMX Connector Client for management communications
	 * with components exposed as JMX MBeans. The urls, user and pwd (password)
	 * parameters define connection parameters to the brokerID(s) through which
	 * management communications will travel.
	 * </p>
	 */
	private void connectJMX() throws Exception {
		model.connect();
		dispatchEvent(new DomainEvent(EVENT.JMX_CONNECT, model, this));
	}
	
	private void disconnectJMX() throws Exception {
		try {
			model.disconnect();
		} catch (Exception e) {
			// Ignore
		} finally {
			model = null;
			dispatchEvent(new DomainEvent(EVENT.JMX_DISCONNECT, model, this));
		}
	}
	
	private boolean isBrokerOnline(SonicMQBroker broker) {
		try {
			Integer replicationState = 
				(Integer)model.getAttribute(
						broker.getObjectName(), 
						"ReplicationState");
			
			switch(replicationState.intValue()) {
			case ReplicationStateConstants.ACTIVE:
			case ReplicationStateConstants.ACTIVE_SYNC:
			case ReplicationStateConstants.STANDALONE:
				return true;
				
			default:
				return false;
			}
		} catch (InstanceNotFoundException e) {
			e.printStackTrace();
			return false;
		} catch (MBeanException e) {
			e.printStackTrace();
			return false;
		} catch (ReflectionException e) {
			e.printStackTrace();
			return false;
		} catch (AttributeNotFoundException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#enumerateBrokers()
	 */
	public List<? extends JMSBroker> enumerateBrokers() throws JMException {
		brokerList.clear();
		int brokersFound = 0;
		try {
			// The collective state is a single management request that contains
			// everything needed to list the brokers in the domain. Resolving the
			// connection URL requires loading configuration beans from the
			// Directory Service, which takes many management round trips per
			// broker; that is deferred until a broker is selected (see
			// resolveBrokerUrl), as is the replication state check.
			IAgentManagerProxy amp = model.getAgentManager();
			IContainerState[] states = (IContainerState[]) amp.getCollectiveState();

			for(IContainerState containerState: states) {
				// Skip offline containers
				if(containerState.getState() != IContainerState.STATE_ONLINE)
					continue;

				for(IComponentState componentState: containerState.getComponentStates()) {
					// Skip offline components
					if(componentState.getState() != IComponentState.STATE_ONLINE)
						continue;

					IIdentity ri = componentState.getRuntimeIdentity();
					IElementIdentity ci = ri.getConfigIdentity();

					final SonicMQBroker.ROLE role;
					if(IBrokerConstants.DS_TYPE.equals(ci.getType())) {
						role = SonicMQBroker.ROLE.PRIMARY;
					} else if(IBackupBrokerConstants.DS_TYPE.equals(ci.getType())) {
						role = SonicMQBroker.ROLE.BACKUP;
					} else {
						continue;
					}

					ObjectName boname = new ObjectName(ri.getCanonicalName());

					String brokerName = boname.getKeyProperty("ID");
					if(brokerName == null) {
						brokerName = ci.getName();
					}
					if(role == SonicMQBroker.ROLE.BACKUP) {
						brokerName = brokerName + " (Backup)";
					}

					brokerList.add(new SonicMQBroker(
							boname,
							brokerName,
							ci.getName(),
							containerState.getContainerHost(),
							role));
					brokersFound++;
				}
			}

			// Dispatch a copy: filterOfflineBrokers may remove entries from
			// brokerList while the UI is still reading this event's list.
			dispatchEvent(new DomainEvent(EVENT.BROKERS_ENUMERATED, new ArrayList<>(brokerList), this));
			if(brokersFound == 0){
				throw new MgmtException("No Brokers Found ... you have possibly not enough privileges");
			}

			filterOfflineBrokers();
			return getBrokerList();
		}
		catch (MgmtException e) {
			//is the case if not administrator priviledged or specifically denied access
			//as is the case in a secure SDM deployment.
			throw new BrokerEnumerationException(e);
		}
	}

	/**
	 * Remove brokers that are not active (offline or standby replicas) from the
	 * broker list and dispatch an updated BROKERS_ENUMERATED event if any were
	 * removed. The unfiltered list is published before this runs so that large
	 * domains show a broker list quickly; checking the replication state costs
	 * two management round trips per broker. Runs inside the enumeration task,
	 * which executes on the domain resource queue and therefore does not block
	 * broker connects (those run on their own resource queue).
	 */
	private void filterOfflineBrokers() {
		List<SonicMQBroker> candidates = new ArrayList<>(brokerList);

		// One thread for the typical small domain; scale up only when there are
		// enough brokers for the check to take long otherwise (~200ms/broker
		// over WAN), capped at 8 for very large domains.
		int threads = Math.max(1, Math.min(8, candidates.size() / 25));
		ExecutorService pool = Executors.newFixedThreadPool(
				threads,
				runnable -> {
					Thread thread = new Thread(runnable, "SonicMQ broker state check");
					thread.setDaemon(true);
					return thread;
				});

		List<SonicMQBroker> online = new ArrayList<>();
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for(final SonicMQBroker broker: candidates) {
				results.add(pool.submit(() -> isBrokerOnline(broker)));
			}
			for(int i = 0; i < candidates.size(); i++) {
				try {
					if(results.get(i).get()) {
						online.add(candidates.get(i));
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} catch (ExecutionException e) {
					// Failed to determine the state, treat the broker as offline
				}
			}
		} finally {
			pool.shutdownNow();
		}

		// Don't publish a stale update when the domain was disconnected while
		// the states were being checked.
		if(model == null)
			return;

		if(online.size() != candidates.size()) {
			brokerList.retainAll(online);
			dispatchEvent(new DomainEvent(EVENT.BROKERS_ENUMERATED, new ArrayList<>(brokerList), this));
		}
	}

	/**
	 * Resolve the JMS connection URL of a broker by loading its configuration
	 * beans from the Directory Service. Deliberately done lazily on first
	 * connect instead of during enumeration: it costs many management round
	 * trips per broker, which makes enumerating large domains take minutes.
	 */
	private void resolveBrokerUrl(SonicMQBroker broker) throws JMSException {
		if(broker.getBrokerURL() != null)
			return;

		try {
			IDirectoryFileSystemService dsProxy = getDirectoryFilesystemService();
			MQMgmtBeanFactory beanFactory = new MQMgmtBeanFactory();
			beanFactory.connect(dsProxy);

			String logicalName = dsProxy.storageToLogical(broker.getConfigStorageName());
			logicalName = logicalName.substring(0, logicalName.lastIndexOf('/'));

			// Find all acceptors for this broker and use the first TCP acceptor
			IAcceptorsBean acceptorsBean;
			if(broker.getRole() == SonicMQBroker.ROLE.BACKUP) {
				acceptorsBean = beanFactory.getBackupBrokerBean(logicalName).getAcceptorsBean();
			} else {
				acceptorsBean = beanFactory.getBrokerBean(logicalName).getAcceptorsBean();
			}

			IAcceptorTcpsBean acceptor = getPrimaryAcceptor(acceptorsBean);
			if(acceptor == null) {
				throw new JMSException("No usable TCP acceptor found for broker " + broker.getBrokerName());
			}

			broker.setBrokerURL(getAcceptorUrl(broker.getContainerHost(), acceptor));
		} catch (MgmtException | DirectoryServiceException | MalformedObjectNameException e) {
			// An alternate URL configured by the user still allows connecting
			// when the acceptor configuration cannot be read.
			String alternateUrl = config.getBrokerPref(
					broker, CoreConfiguration.PREF_BROKER_ALTERNATE_URL, null);
			if(alternateUrl != null && alternateUrl.length() > 0) {
				broker.setBrokerURL(alternateUrl);
				return;
			}

			JMSException jmse = new JMSException(
					"Unable to determine the connection URL for broker " + broker.getBrokerName() + ": " + e);
			jmse.setLinkedException(e);
			throw jmse;
		}
	}

	/**
	 * Determine the URL to connect to an acceptor on a SonicMQ broker:
	 * <p>
	 * - When the acceptor has the external URL property configured, use that;
	 * <p>
	 * - If the hostname of the broker URI is "localhost" or "127.0.0.1", find out what the
	 * hostname is of the container the broker is deployed in and use that; This solves the 
	 * "acceptors on localhost" problem when connecting remotely with SMM.
	 * <p>
	 * 
	 * @param containerHost
	 * @param acceptor	 *
	 * @return
	 */
	private String getAcceptorUrl(String containerHost, IAcceptorTcpsBean acceptor) throws MgmtException {
		String acceptorUrl = acceptor.getAcceptorUrl();
				
		// Try to retrieve the external acceptor URL (if the property exists).
		try {
			String externalUrlValue = acceptor.getStringAttribute("ACCEPTOR_EXTERNAL_URL");
			if(externalUrlValue != null && externalUrlValue.length() > 0) {
				// See if the URL is set correctly by parsing it.
				URI externalUrl = new URI(externalUrlValue);
				return externalUrl.toString();
			}
		} catch (URISyntaxException e) {
			// Apparently, the external URL was set incorrectly or something... do nothing
			// and try to establish the connection URL some other way.
		} catch (com.sonicsw.ma.mgmtapi.config.AttributeNotFoundException e) {
			// There is no external URL property, continue on.
		}
		
		try {
			URI uri = new URI(acceptorUrl);
			
			if("localhost".equalsIgnoreCase(uri.getHost())
			|| "127.0.0.1".equalsIgnoreCase(uri.getHost())) {			
				return new URI(
					uri.getScheme(),
					uri.getUserInfo(),
					containerHost,
					uri.getPort(),
					uri.getPath(),
					uri.getQuery(),
					uri.getFragment()).toASCIIString();
			} else {
				return acceptorUrl;
			}
		} catch (URISyntaxException e) {
			e.printStackTrace();
			return acceptor.getAcceptorUrl();
		}
	}

	//TODO: need abilitiy for user to choose
	private IAcceptorTcpsBean getPrimaryAcceptor(IAcceptorsBean acceptorsBean) throws MgmtException {
		IDefaultAcceptorsType defaultAcceptors = acceptorsBean.getDefaultAcceptors();
		IMgmtBeanBase primaryAcceptorRef = defaultAcceptors.getPrimaryAcceptorRef();
		if(primaryAcceptorRef instanceof IAcceptorTcpsBean) {
			IAcceptorTcpsBean primaryAcceptor = (IAcceptorTcpsBean) primaryAcceptorRef;
			// this will only work for ssl if truststore set properly and soni_SSL.jar is on cp
			return primaryAcceptor;
		}
		
		// The primary acceptor is not usable, try to find another one.
		IAcceptorMapType acceptorsList = acceptorsBean.getAcceptors();
		
		for(Object key: acceptorsList.getKeyNames()) {
			IMgmtBeanBase acceptorBean = acceptorsList.getItem((String)key);
			
			if(IAcceptorTcpsBean.class.isAssignableFrom(acceptorBean.getClass())) {
				IAcceptorTcpsBean acceptor = (IAcceptorTcpsBean)acceptorBean;
				// We found a good acceptor, use it.
				return acceptor;
			}
		}

		// No usable acceptors found
		return null;
	}
		
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#enumerateQueues(nl.queuemanager.smm.jms.JMSBroker, java.lang.String)
	 */
	public void enumerateQueues(JMSBroker broker, String filter) {
		List<JMSQueue> queues = getQueueList(broker, filter);
		dispatchEvent(new DomainEvent(EVENT.QUEUES_ENUMERATED,  new BrokerDestinations(broker,queues), this));
	}

	public void enumerateTopics(JMSBroker broker, String filter) throws Exception{
		List<JMSTopic> topics = new ArrayList<JMSTopic>(); // empty list
		dispatchEvent(new DomainEvent(EVENT.TOPICS_ENUMERATED,  new BrokerDestinations(broker,topics), this));
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#getQueueList(nl.queuemanager.smm.jms.JMSBroker, java.lang.String)
	 */
	@SuppressWarnings("unchecked")
	public List<JMSQueue> getQueueList(JMSBroker broker, String filter) {
		// Get the queues
		List<IQueueData> temp = null;
		
		try {
			temp = (List<IQueueData>)model.invoke(
					((SonicMQBroker)broker).getObjectName(), 
					"getQueues", 
					new Object[] {filter}, 
					new String[] {String.class.getName()});
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
		ArrayList<JMSQueue> queues = CollectionFactory.newArrayList();
		
		// Check for temporary queues and other special queues. Access to the
		// SonicMQ.routingQueue is disallowed by the Management API so there is
		// no sense in showing it. SonicMQ.deadMessage however can be useful so
		// it is not filtered.
		for (IQueueData queue: temp) {
			if (!queue.isTemporaryQueue() && !"SonicMQ.routingQueue".equals(queue.getQueueName()))
				queues.add(new SonicMQQueue((SonicMQBroker)broker, queue));
		}

		return queues;
	}

	/**
	 * Get topics that have a durable subscription on them for a certain broker.
	 * 
	 * @param broker	 *
	 * @return
	 * @throws MalformedObjectNameException
	 * @throws NullPointerException
	 * @throws InstanceNotFoundException
	 * @throws MBeanException
	 * @throws ReflectionException
	 */
//	public List<JMSTopic> getDurableTopicList(JMSBroker broker, String filter)throws MalformedObjectNameException, NullPointerException, InstanceNotFoundException, MBeanException, ReflectionException {
//		// Get user with DurableSubscriptions;
//		if (filter == null)
//			filter = "";
//		
//		List<String> users = getUsersWithDurableSubscriptionsList(broker, filter); 
//		List<JMSTopic> topics = new ArrayList<JMSTopic>();
//		HashMap<String, JMSTopic> tmptopics =  new HashMap<String, JMSTopic>();
//		for(String user: users){
//			List<JMSTopic> idsTopics = getDurableSubscriptionsList(broker, user, filter);
//			for(JMSTopic idsTopic: idsTopics) {
//				if(!tmptopics.containsKey(idsTopic.getName())){
//					tmptopics.put(idsTopic.getName(), idsTopic);
//				}
//			}
//		}
//		topics.addAll(tmptopics.values());
//		
//		dispatchEvent(new DomainEvent(EVENT.TOPICS_ENUMERATED, topics, this));
//		return topics;
//	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#createTopic(nl.queuemanager.smm.jms.JMSBroker, java.lang.String)
	 */
	public JMSTopic createTopic(JMSBroker broker, String topicName) {
		return new SonicMQTopic((SonicMQBroker)broker, topicName);
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#createQueue(nl.queuemanager.smm.jms.JMSBroker, java.lang.String)
	 */
	public JMSQueue createQueue(JMSBroker broker, String queueName) {
		return new SonicMQSimpleQueue((SonicMQBroker)broker, queueName);
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#enumerateMessages(nl.queuemanager.smm.jms.JMSQueue)
	 */
	@SuppressWarnings("unchecked")
	public Enumeration<Message> enumerateMessages(JMSQueue queue) throws JMSException {
		QueueBrowser browser = createQueueBrowser(queue);
		return new MessageEnumerationWrapper(browser.getEnumeration());
	}
	
	/**
	 * Open an asynchronous consumer for the specified destination.
	 * 
	 * @param destination
	 */
	private MessageConsumer openASyncConsumer(JMSDestination destination) throws JMSException {
		SonicMQConnection connection = brokerConnections.get(destination.getBroker());
		return connection.getASyncConsumer(destination);
	}

	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#openConsumer(nl.queuemanager.smm.jms.JMSDestination, javax.jms.MessageListener)
	 */
	public MessageConsumer openConsumer(JMSDestination destination, MessageListener listener) throws JMSException {
		MessageConsumer consumer = new SonicMQMessageConsumer(openASyncConsumer(destination));
		consumer.setMessageListener(listener);
		return consumer;
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#sendMessage(nl.queuemanager.smm.jms.JMSDestination, javax.jms.Message)
	 */

	public void sendMessage(JMSDestination destination, javax.jms.Message messageToSend) throws JMSException {
		SonicMQConnection connection = brokerConnections.get(destination.getBroker());
		progress.message.jclient.Session session = connection.getSyncSession();
		
		Message jmsMessage = 
			SonicMQMessageConverter.convertMessage(session, messageToSend);
		
		MessageProducer producer = connection.getMessageProducer(destination);
		long timetolive = producer.getTimeToLive();
		if(messageToSend.getJMSExpiration() != 0) {
			timetolive = messageToSend.getJMSExpiration() - messageToSend.getJMSTimestamp();
		}
		producer.send(jmsMessage, jmsMessage.getJMSDeliveryMode(), jmsMessage.getJMSPriority(), timetolive);
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#forwardMessage(nl.queuemanager.smm.jms.JMSQueue, nl.queuemanager.smm.jms.JMSDestination, java.lang.String)
	 */
	public void forwardMessage(JMSQueue from, JMSDestination to, String messageID) throws JMSException {
		if(!from.getBroker().equals(to.getBroker())) {
			throw new IllegalArgumentException("Forwarding is only supported between queues on the same broker!");
		}
		
		SonicMQConnection connection = brokerConnections.get(from.getBroker());
		final Session session = connection.getSyncSession();
		final String selector = "JMSMessageID = '" + messageID + "'";
		
		final MessageConsumer consumer = session.createConsumer(
				session.createQueue(from.getName()), 
				selector);
		Destination destination = (JMSDestination.TYPE.QUEUE == to.getType()) ?
				session.createQueue(to.getName()) :
				session.createTopic(to.getName());
		
		final progress.message.jimpl.Message m = 
			(progress.message.jimpl.Message) consumer.receiveNoWait();
		if(m != null) {
			m.acknowledgeAndForward(destination);
		} else {
			throw new RuntimeException("Attempt to receive message with id " +
				messageID + " from queue " + from + " failed. No message was received. " +
				"The message selector was: " + selector);
		}
		
		consumer.close();
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#deleteMessages(java.util.List)
	 */
	public void deleteMessages(List<JMSQueue> queueList) throws JMSException, InstanceNotFoundException, MBeanException, ReflectionException {
		if(queueList.size() == 0)
			return;
		
		for(JMSQueue queue: queueList) {
			// First try to open a browser on the queue. If this fails, the user is 
			// not able to receive from this queue. They must not be allowed to 
			// delete messages they are not allowed to read!
			createQueueBrowser(queue).close();
			
			// Now delete the messages on this queue
			ArrayList<String> queueNames = CollectionFactory.newArrayList();
			queueNames.add(queue.getName());
			
			model.invoke(
					((SonicMQBroker)queue.getBroker()).getObjectName(), 
					"deleteQueueMessages", 
					new Object[] {queueNames}, 
					new String[] {ArrayList.class.getName()});
		}
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#deleteMessages(nl.queuemanager.smm.jms.JMSQueue, java.util.List)
	 */
	public void deleteMessages(JMSQueue queue, List<Message> messages) throws JMSException {
		SonicMQConnection connection = brokerConnections.get(queue.getBroker());
		Session session = connection.getSyncSession();
		
		for(final Message m: messages) {
			String selector = "JMSMessageID = '" + m.getJMSMessageID() + "'";
			
			MessageConsumer consumer = session.createConsumer(
					session.createQueue(queue.getName()), 
					selector);
			
			Message msg = null;
			while((msg = consumer.receiveNoWait()) != null)
				msg.acknowledge();
			consumer.close();
		}
	}
	
	private QueueBrowser createQueueBrowser(JMSQueue queue) throws JMSException {
		SonicMQConnection connection = brokerConnections.get(queue.getBroker());
		Session session = connection.getSyncSession();
		
		return session.createBrowser(session.createQueue(queue.getName()));
	}

	private IDirectoryFileSystemService getDirectoryFilesystemService() throws MalformedObjectNameException {
		return model.getDirectoryService();
	}
	
	private List<SonicMQBroker> getBrokerList() {
		return brokerList;
	}
	
	/* (non-Javadoc)
	 * @see nl.queuemanager.smm.sonic.JMSDomain#connectToBroker(nl.queuemanager.smm.jms.JMSBroker)
	 */
	public void connectToBroker(JMSBroker aBroker, Credentials credentials) throws JMSException {
		SonicMQBroker broker = (SonicMQBroker)aBroker;

		if(brokerConnections.get(broker) == null) {
			// This check was part of broker enumeration; it is done lazily now
			// so that enumerating a large domain stays fast.
			if(!isBrokerOnline(broker)) {
				throw new JMSException("Broker " + broker.getBrokerName() +
						" is not active (offline or standby replica)");
			}
			resolveBrokerUrl(broker);
			if(credentials == null)
				credentials = getDefaultCredentials(broker);
			connectJMS(broker, credentials);
		}

		dispatchEvent(new DomainEvent(EVENT.BROKER_CONNECT, broker, this));
	}
	
	/**
	 * Return the SonicMQConnection belonging to the broker (if there is one).
	 * 
	 * @param broker
	 */
	SonicMQConnection getConnection(JMSBroker broker) {
		return brokerConnections.get(broker);
	}
	
	@Override
	public Credentials getCredentials(JMSBroker broker, Credentials def, Exception exception) {
		BrokerCredentialsDialog dialog = credentialsDialogProvider.get();
		try {
			exception.printStackTrace();
			dialog.enableAlternateUrlOverride(config, ((SonicMQBroker)broker).getBrokerURL());
			return dialog.getCredentials(broker, def, exception);
		} finally {
			if(dialog != null) {
				dialog.dispose();
			}
		}
	}

	private Credentials getDefaultCredentials(SonicMQBroker broker) {
		return new BasicCredentials(model.getUserName(), model.getPassword());
	}
	
	private void connectJMS(SonicMQBroker broker, Credentials cred) throws JMSException {
		if(brokerConnections.get(broker) != null)
			return;
		
		if(broker == null)
			throw new IllegalArgumentException("Broker must be supplied");
		
		if(cred == null)
			throw new IllegalArgumentException("Credentials must be supplied");
		
		// Try the configuration to get an alternate URL if one is configured.
		String brokerUrl = config.getBrokerPref(
				broker, CoreConfiguration.PREF_BROKER_ALTERNATE_URL, broker.getBrokerURL());
		
		progress.message.jclient.ConnectionFactory factory = 
			new progress.message.jclient.ConnectionFactory(
				broker.getBrokerURL());
		try {
			if(cred instanceof BasicCredentials) {
				factory.setDefaultUser(((BasicCredentials) cred).getUsername());
				factory.setDefaultPassword(((BasicCredentials) cred).getPassword());
			} else {
				cred.apply(factory);
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new JMSException("Unable to apply credentials to connectionfactory: " + e.toString());
		}
		
		String loginSPI = System.getProperty("smm.jms.LoginSPI", null);
		if(loginSPI != null) {
			factory.setLoginSPI(loginSPI);
		}
		
		factory.setConnectID(null);
		factory.setConnectionURLs(brokerUrl);
		factory.setSequential(true);
		
		// Load balancing off, we don't want to go connecting to another broker in the same
		// cluster.
		factory.setLoadBalancing(false);
		
		// Fault tolerance on, so we fail gracefully to the backup broker. This allows the user
		// to save his work instead of just crashing the program.
		factory.setFaultTolerant(true);

		progress.message.jclient.Connection connection = 
			(progress.message.jclient.Connection) factory.createConnection();
		connection.setPingInterval(5000);
		connection.setExceptionListener(new SonicExceptionListener());
		
		progress.message.jclient.Session syncSession = (progress.message.jclient.Session) 
			connection.createSession(false, progress.message.jclient.Session.SINGLE_MESSAGE_ACKNOWLEDGE);
		progress.message.jclient.Session asyncSession = (progress.message.jclient.Session) 
			connection.createSession(false, progress.message.jclient.Session.SINGLE_MESSAGE_ACKNOWLEDGE);
		
		brokerConnections.put(broker, new SonicMQConnection(broker, connection, syncSession, asyncSession));
		connection.start();		
	}
	
	/**
	 * Disconnect all brokers and send a disconnect event for each one
	 */
	private void disconnectJMS() {
		if(brokerConnections == null)
			return;
		
		for(Iterator<Entry<SonicMQBroker, SonicMQConnection>> it = brokerConnections.entrySet().iterator(); it.hasNext();) {
			Entry<SonicMQBroker, SonicMQConnection> entry = it.next();
			SonicMQBroker broker = entry.getKey();
			SonicMQConnection con = entry.getValue();
			
			try {
				if(con.getConnection() != null) {
					con.getConnection().close();
				}
			} catch (JMSException e) {
			} finally {				
				dispatchEvent(new DomainEvent(EVENT.BROKER_DISCONNECT, broker, this));
			}
		}
	}	
	
	private void dispatchEvent(Object event) {
		eventBus.post(event);
	}
	
	private static class SonicExceptionListener implements ExceptionListener {
		public void onException(JMSException ex) {
			System.out.println("EXCEPTION CAUGHT!");
			
			// TODO: Disconnect the broker and alert the user. DO NOT make the user lose any work,
			// only notify of the connection being broken.
			System.out.println(ex);
		}
	}
	
	/**
	 * Wraps a normal QueueBrowser message Enumeration to ensure that any
	 * Multipart- or XMLMessages are properly wrapped in a SonicMQ* equivalent.
	 * 
	 * @author Gerco Dries (gdr@progaia-rs.nl)
	 *
	 */
	private static class MessageEnumerationWrapper implements Enumeration<Message> {
		private final Enumeration<Message> delegate;
		
		public MessageEnumerationWrapper(Enumeration<Message> delegate) {
			this.delegate = delegate;
		}
		
		public boolean hasMoreElements() {
			return delegate.hasMoreElements();
		}

		public Message nextElement() {
			Message ret = delegate.nextElement();
			if(ret instanceof MultipartMessage) {
				return new SonicMQMultipartMessage((MultipartMessage)ret);
			} else if(ret instanceof XMLMessage) {
				return new SonicMQXMLMessage((XMLMessage)ret);
			}
			return ret;
		}
	}

	public List<String> getPredefinedPropertyNames() {
		List<String> ret = new ArrayList<>();
		ret.add("JMSType");
		ret.add("JMS_SonicMQ_preserveUndelivered");
		ret.add("JMS_SonicMQ_notifyUndelivered");
		ret.add("JMS_SonicMQ_destinationUndelivered");
		return ret;
	}
	
}
