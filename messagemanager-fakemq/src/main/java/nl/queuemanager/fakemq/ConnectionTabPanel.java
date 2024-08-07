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
package nl.queuemanager.fakemq;

import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;
import nl.queuemanager.core.task.Task;
import nl.queuemanager.core.task.TaskExecutor;
import nl.queuemanager.ui.CommonUITasks;
import nl.queuemanager.ui.CommonUITasks.Segmented;
import nl.queuemanager.ui.UITab;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

@SuppressWarnings("serial")
public class ConnectionTabPanel extends JPanel implements UITab {
	private final FakeMQDomain domain;
	private final TaskExecutor worker;
	private final EventBus eventBus;
	
	@Inject
	public ConnectionTabPanel(FakeMQDomain domain, TaskExecutor worker, EventBus eventBus) {
		this.domain = domain;
		this.worker = worker;
		this.eventBus = eventBus;
		
		add(createNewConnectionButton());
	}

	private JButton createNewConnectionButton() {
		JButton button = CommonUITasks.createButton("New Connection",
		new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				connect();
			}
		});
		CommonUITasks.makeSegmented(button, Segmented.ONLY);
		return button;
	}

	public void connect() {
		worker.execute(new Task(domain, eventBus) {
			@Override
			public void execute() throws Exception {
				domain.connect();
			}
			@Override
			public String toString() {
				return "Connecting to FakeMQ";
			}
		});
	}

	public String getUITabName() {
		return "Connection";
	}
	
	public JComponent getUITabComponent() {
		return this;
	}

	public ConnectionState[] getUITabEnabledStates() {
		return ConnectionState.values();
	}

}
