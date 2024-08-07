package nl.queuemanager.core;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import nl.queuemanager.core.jms.DomainEvent;
import nl.queuemanager.core.task.TaskExecutor;
import nl.queuemanager.core.tasks.TaskFactory;

/**
 * Listens for applicationwide domain events and reacts accordingly. Currently, this is only the
 * JMX_CONNECT event, that triggers enumeration of brokers in the domain.
 * 
 * @author gerco
 *
 */
public class GlobalDomainEventListener {

	private final TaskFactory taskFactory;
	private final TaskExecutor worker;

	@Inject
	public GlobalDomainEventListener(TaskFactory taskFactory, TaskExecutor worker) {
		this.taskFactory = taskFactory;
		this.worker = worker;
	}

	@Subscribe
	public void processEvent(DomainEvent event) {
		switch(event.getId()) {
			case JMX_CONNECT:
				enumerateBrokers();
				break;
		}
	}

	private void enumerateBrokers() {
		worker.execute(taskFactory.enumerateBrokers());
	}
	
}
