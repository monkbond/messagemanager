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

import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import nl.queuemanager.core.Pair;
import nl.queuemanager.core.jms.JMSDomain;
import nl.queuemanager.core.jms.JMSFeature;
import nl.queuemanager.core.task.TaskExecutor;
import nl.queuemanager.core.tasks.FireRefreshRequiredTask;
import nl.queuemanager.core.tasks.FireRefreshRequiredTask.JMSDestinationHolder;
import nl.queuemanager.core.tasks.TaskFactory;
import nl.queuemanager.jms.JMSDestination;
import nl.queuemanager.jms.JMSQueue;

import javax.jms.Message;
import javax.swing.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("serial")
class JMSDestinationTransferHandler extends TransferHandler {
	private static DataFlavor urlDataFlavor;
	
	private final JMSDomain domain;
	private final TaskExecutor worker;
	private final TaskFactory taskFactory;
	private final EventBus eventBus;

	// The object to read JMSDestinations from
	private final JMSDestinationHolder destinationHolder;
	
	private int sourceActions;
	
	static {
		try {
			urlDataFlavor = new DataFlavor("application/x-java-url;class=java.net.URL");
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
	}

	@Inject
	public JMSDestinationTransferHandler(
			JMSDomain domain,
			TaskExecutor worker, 
			TaskFactory taskFactory,
			EventBus eventBus,
			@Assisted JMSDestinationHolder destinationHolder) 
	{
		setSourceActions(COPY);
		this.domain = domain;
		this.worker = worker;
		this.destinationHolder = destinationHolder;
		this.taskFactory = taskFactory;
		this.eventBus = eventBus;
	}

	/**
	 * Create a Transferable for when a JMSDestination is dragged or copied.
	 */
	@Override
	protected Transferable createTransferable(JComponent c) {
		return new JMSDestinationInfoTransferable(destinationHolder.getJMSDestinationList());
	}
	
	/**
	 * Determines if this TransferHandler is able to import one of the supplied 
	 * dataflavors. By default accepts messageIdList, messageList and javaFileList.
	 */
	@Override
	public boolean canImport(JComponent jcomponent, DataFlavor[] dataflavors) {
		for(DataFlavor flavor: dataflavors) {
			if(flavor.equals(MessageListTransferable.messageIDListDataFlavor))
				return domain.isFeatureSupported(JMSFeature.FORWARD_MESSAGE);

			if(flavor.equals(MessageListTransferable.messageListDataFlavor))
				return true;
				// uses sendMessage internally
				// 	return domain.isFeatureSupported(JMSFeature.FORWARD_MESSAGE);
			
			if(flavor.equals(DataFlavor.javaFileListFlavor))
				return true;
			
			if(flavor.equals(urlDataFlavor))
				return true;
		}
		
		return false;
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean importData(JComponent component, Transferable transferable) {
		try {
			DataFlavor[] flavors = transferable.getTransferDataFlavors();
			for(DataFlavor f: flavors) {
				// TODO: Implement preference
				if(f.equals(MessageListTransferable.messageIDListDataFlavor)) {
					final List<Pair<JMSQueue, String>> messageIDList = 
						(List<Pair<JMSQueue, String>>) transferable.getTransferData(
								MessageListTransferable.messageIDListDataFlavor);
					return importMessageIDList(destinationHolder, messageIDList);
				}
				
				if(f.equals(MessageListTransferable.messageListDataFlavor)) {
					final List<Message> messageList = (List<Message>) 
						transferable.getTransferData(MessageListTransferable.messageListDataFlavor);

					return importMessageList(destinationHolder, messageList);
				}
				
				if(f.equals(DataFlavor.javaFileListFlavor)) {
					final List<File> fileList = (List<File>)transferable.getTransferData(DataFlavor.javaFileListFlavor);
					return importFileList(destinationHolder, fileList);
				}
				
				if(f.equals(urlDataFlavor)) {
					try {
						URL url = (URL)transferable.getTransferData(urlDataFlavor);
						if(url != null && "file".equals(url.getProtocol())) {
							URI uri = url.toURI();
							
							// Cannot use File(URI) because the authority component is non-null on Mac OS X 10.7
							File file = new File(uri.getPath());
							
							return importFileList(destinationHolder, Collections.singletonList(file));
						} else {
							System.out.println("Protocol is not file?!?!");
						}
					} catch (URISyntaxException e) {
						System.out.println(e);
						e.printStackTrace();
					} catch (Throwable t) {
						t.printStackTrace();
					}
				}
			}
		} catch (UnsupportedFlavorException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return true;
	}	
	
	/**
	 * Import a list of message ids into the component
	 */
	protected boolean importMessageIDList(FireRefreshRequiredTask.JMSDestinationHolder destinationHolder, final List<Pair<JMSQueue, String>> messageList) {
		final JMSQueue toQueue = (JMSQueue)destinationHolder.getJMSDestination();
		
		worker.executeInOrder(
			taskFactory.moveMessages(toQueue, messageList),
			taskFactory.enumerateQueues(toQueue.getBroker(), null),
			taskFactory.fireRefreshRequired(destinationHolder,toQueue));
			//new FireRefreshRequiredTask(null, eventBus, destinationHolder, toQueue));
		
		return true;
	}

	/**
	 * Import a list of {@link Message} objects into the component.	 *
	 */
	protected boolean importMessageList(FireRefreshRequiredTask.JMSDestinationHolder destinationHolder, List<Message> messageList) {
		final JMSDestination destination = destinationHolder.getJMSDestination();
		
		worker.executeInOrder(
			taskFactory.sendMessages(destination, messageList),
			taskFactory.enumerateQueues(destination.getBroker(), null),
			taskFactory.fireRefreshRequired(destinationHolder,destination));
			//new FireRefreshRequiredTask(null, eventBus, destinationHolder, destination));
		
		return true;
	}

	/**
	 * Import a list of files into the component.
	 */
	protected boolean importFileList(FireRefreshRequiredTask.JMSDestinationHolder destinationHolder, List<File> fileList) {
		final JMSDestination destination = destinationHolder.getJMSDestination();
		
		worker.executeInOrder(
			taskFactory.sendFiles(destination, fileList, null),
			taskFactory.enumerateQueues(destination.getBroker(), null),
			taskFactory.fireRefreshRequired(destinationHolder, destination));
		// with this the task did never receive events
			//new FireRefreshRequiredTask(null, eventBus, destinationHolder, destination));
		
		return true;
	}


	/**
	 * Set the source actions supported by this TransferHandler.
	 * 
	 * @param sourceActions
	 */
	protected void setSourceActions(int sourceActions) {
		this.sourceActions = sourceActions;
	}
	
	@Override
	public int getSourceActions(JComponent c) {
		return sourceActions;
	}

}
