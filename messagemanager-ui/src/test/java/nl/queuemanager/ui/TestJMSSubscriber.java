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
package nl.queuemanager.ui;

import nl.queuemanager.core.MessageBuffer;
import nl.queuemanager.core.configuration.CoreConfiguration;
import nl.queuemanager.core.task.TaskExecutor;
import nl.queuemanager.jms.JMSDestination;
import nl.queuemanager.jms.impl.MessageFactory;
import nl.queuemanager.test.support.SynchronousTaskExecutor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.jms.Message;
import java.util.Collections;
import java.util.Observer;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TestJMSSubscriber {
	private CoreConfiguration config;
	private TaskExecutor worker;
	private JMSSubscriber subscriber;
	private JMSDestination destination;
	private MessageBuffer buffer;
	private Observer observer;
	
	@Before
	public void before() {
		config = mock(CoreConfiguration.class);
		when(config.getUserPref(CoreConfiguration.PREF_MAX_BUFFERED_MSG, "50")).thenReturn("50");
		
		worker = new SynchronousTaskExecutor();
		buffer = new MessageBuffer();
		subscriber = new JMSSubscriber(null, worker, config, null, destination, buffer);
		verify(config).getUserPref(CoreConfiguration.PREF_MAX_BUFFERED_MSG, "50");
		
		observer = mock(Observer.class);
		subscriber.addObserver(observer);
	}
	
	@After
	public void after() {
		verifyNoMoreInteractions(config, observer);
	}
	
	@Test
	public void testReceiveMessage() {
		buffer.onMessage(MessageFactory.createMessage());

		verify(observer).update(eq(subscriber), any());
		
		assertEquals(1, subscriber.getMessageCount());
	}
	
	@Test
	public void testProcessEvent() {
		subscriber.processEvent(null);
		verify(observer).update(eq(subscriber), any());
	}

	@Test
	public void testRemoveMessages() {
		Message message = MessageFactory.createMessage();
		
		buffer.onMessage(message);
		verify(observer).update(eq(subscriber), any());
		
		assertEquals(1, subscriber.getMessageCount());
		
		buffer.remove(Collections.singletonList(message));
		
		assertEquals(0, subscriber.getMessageCount());
	}

	@Test
	public void testClear() {
		buffer.onMessage(MessageFactory.createMessage());
		buffer.onMessage(MessageFactory.createMessage());
		
		verify(observer, times(2)).update(eq(subscriber), any());
		
		assertEquals(2, subscriber.getMessageCount());
	}
	
	@Test
	public void testLockMessage() {
		Message message = MessageFactory.createMessage();

		buffer.setMaximumNumberOfMessages(3);
		
		buffer.onMessage(message);
		buffer.onMessage(MessageFactory.createMessage());
		buffer.onMessage(MessageFactory.createMessage());
		
		assertEquals(3, buffer.getMessageCount());
		
		buffer.lockMessage(message);
		buffer.onMessage(MessageFactory.createMessage());
		buffer.onMessage(MessageFactory.createMessage());
		buffer.onMessage(MessageFactory.createMessage());
		
		assertEquals(3, buffer.getMessageCount());
		assertTrue(buffer.getMessages().contains(message));
		
		verify(observer, times(9)).update(eq(subscriber), any());
	}
	
	@Test
	public void testUnlockMessage() {
		buffer.setMaximumNumberOfMessages(3);
		Message message = MessageFactory.createMessage();
		
		// Send the message to be locked
		buffer.onMessage(message);
		buffer.lockMessage(message);
		
		// Fill up the buffer
		buffer.onMessage(MessageFactory.createMessage());
		buffer.onMessage(MessageFactory.createMessage());
		buffer.onMessage(MessageFactory.createMessage());
		
		assertEquals(3, buffer.getMessageCount());
		
		// Unlock the message and send another to make it disappear;
		buffer.unlockMessage(message);
		buffer.onMessage(MessageFactory.createMessage());

		assertFalse(buffer.getMessages().contains(message));
		
		verify(observer, times(7)).update(eq(subscriber), any());
	}
	
	@Test
	public void testLockAll() {
		buffer.setMaximumNumberOfMessages(3);
		Message m1 = MessageFactory.createMessage();
		Message m2 = MessageFactory.createMessage();
		Message m3 = MessageFactory.createMessage();
		Message m4 = MessageFactory.createMessage();
		
		buffer.onMessage(m1);
		buffer.lockMessage(m1);
		buffer.onMessage(m2);
		buffer.lockMessage(m2);
		buffer.onMessage(m3);
		buffer.lockMessage(m3);
		verify(observer, times(3)).update(eq(subscriber), any());
		
		assertEquals(3, buffer.getMessageCount());
		
		buffer.onMessage(m4);
		verify(observer, times(4)).update(eq(subscriber), any());
		
		assertEquals(4, buffer.getMessageCount());		
	}
	
	@Test
	public void testUnlockAll() {
		buffer.setMaximumNumberOfMessages(3);
		Message m1 = MessageFactory.createMessage();
		Message m2 = MessageFactory.createMessage();
		Message m3 = MessageFactory.createMessage();
		Message m4 = MessageFactory.createMessage();
		
		buffer.onMessage(m1);
		buffer.lockMessage(m1);
		buffer.onMessage(m2);
		buffer.lockMessage(m2);
		buffer.onMessage(m3);
		buffer.lockMessage(m3);
		verify(observer, times(3)).update(eq(subscriber), any());
		
		assertEquals(3, buffer.getMessageCount());
		
		buffer.onMessage(m4);
		verify(observer, times(4)).update(eq(subscriber), any());
		
		assertEquals(4, buffer.getMessageCount());		

		buffer.unlockAll();
		
		buffer.onMessage(MessageFactory.createMessage());
		verify(observer, times(7)).update(eq(subscriber), any());

		assertEquals(3, buffer.getMessageCount());
	}

	@Test
	public void testGetDestination() {
		assertEquals(destination, subscriber.getDestination());
	}
}
