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

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import nl.queuemanager.core.DebugProperty;
import nl.queuemanager.core.jms.DomainEvent;
import nl.queuemanager.core.task.Task;
import nl.queuemanager.core.task.TaskEvent;
import nl.queuemanager.core.util.CoreException;
import nl.queuemanager.core.util.UserCanceledException;

import javax.jms.JMSSecurityException;
import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Logger;

/**
 * Listens for TASK_ERROR events and alerts the user to them.
 * 
 * @author Gerco Dries (gdr@progaia-rs.nl)
 *
 */
@Singleton
public class ErrorListener {
	private static final String MANAGE_PERMISSION_DENIED = 
		"com.sonicsw.mf.common.security.ManagePermissionDeniedException";

	private static final String CONFIGURE_PERMISSION_DENIED = 
		"com.sonicsw.mf.common.security.ConfigurePermissionDeniedException";
	
	private static final boolean DEBUG = "TRUE".equalsIgnoreCase(System.getProperty("developer")) 
			|| DebugProperty.developer.isEnabled();
	
	private final Logger log = Logger.getLogger(getClass().getName());
	
	private Component parent;
	
	@Inject
	public ErrorListener(JFrame parent) {
		this.parent = parent;
	}

	@Subscribe
	public void handleDomainEvent(DomainEvent event) {
		log.fine(Thread.currentThread() + " -> " + event);
		switch(event.getId()) {
			case ASYNC_ERROR:
				String message = translateExceptionMessage((Throwable)event.getInfo());
				showMessage(parent, "Error: " + event.getSource().toString(), message, true);
				break;
		}
	}


	@Subscribe
	public void handleTaskEvent(TaskEvent event) {
		log.fine(Thread.currentThread() + " -> " + event);
		
		switch(event.getId()) {
			case TASK_ERROR:
				if(event.getInfo() instanceof UserCanceledException || (event.getInfo()!=null && ((Throwable)event.getInfo()).getCause() instanceof UserCanceledException)) {
					// If the user canceled something, we don't want to bother
					// them with another message dialog.
					return;
				}

				if(!((Task)event.getSource()).isBackground() || DEBUG) {
					String message = translateExceptionMessage((Throwable)event.getInfo());
					showMessage(parent, "Error in task " + event.getSource().toString(), message, true);
				}
				else{
					// even if the task was a background task, we still want to show the error on the console
					log.info(translateExceptionMessage((Throwable)event.getInfo()));
				}

				break;
			}
	}

	private String translateExceptionMessage(Throwable e) {
		if(e == null)
			return "Unknown error! (Exception in TaskEvent was null)";
		
		if(DEBUG) return captureStackTrace(e);

		// Handle known Exceptions first. If the Exception is unknown, dig deeper
		if(e instanceof java.net.UnknownHostException)
			return "The host was not found";

		if( e instanceof java.lang.module.ResolutionException ||
			e instanceof java.lang.module.FindException){
			return "Please check the Example/Template profile for recommended JARs on the classpath<br>and adjust the classpath: <br><br>" + e.getMessage() + "<br><br>More Information: <a href='https://conapi-oss.github.io/messagemanager/docs/troubleshooting/howto-classloadingissue'>How To Fix Class Loading Issues</a>";
		}

		if(MANAGE_PERMISSION_DENIED.equals(e.getClass().getName())
		|| CONFIGURE_PERMISSION_DENIED.equals(e.getClass().getName()))
			return "You do not have permission to perform this action";

		if(e instanceof JMSSecurityException)
			return "Access denied: " + e.getMessage();
		
		if(e instanceof CoreException)
			return e.getMessage();
		
		// The Exception was not a known one. Dig deeper.
		if(e.getCause() != null && e.getCause() != e)
			return //"[translateExceptionMessage(" + e.getClass().getName() + ")] " + 
				translateExceptionMessage(e.getCause());

		// Reached the end of the exception stack
		return e.getMessage();
	}

	private String captureStackTrace(Throwable e) {
		// Capture the stacktrace for in the error message
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		
		return sw.toString();
	}

	private void showMessage(final Component parent, final String title, final String message, final boolean error) {


		if(!DEBUG){
			// add a contact us link
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					JOptionPane.showMessageDialog(parent,
							new EditorPaneWithHyperlinks(message + "<br><br><a href='mailto:support@conapi.at'>Contact Support</a>"), title,
							error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
				}
			});
		}
		else{
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					JOptionPane.showMessageDialog(parent,
							message, title,
							error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
				}
			});
		}
	}
}