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
package nl.queuemanager;

import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import nl.queuemanager.core.Configuration;
import nl.queuemanager.core.CoreModule;
import nl.queuemanager.core.configuration.XmlConfigurationModule;
import nl.queuemanager.core.events.ApplicationInitializedEvent;
import nl.queuemanager.core.platform.PlatformHelper;
import nl.queuemanager.ui.MMFrame;
import nl.queuemanager.ui.UIModule;

import com.google.common.eventbus.EventBus;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Stage;

public class Main {

	// Do not allow instances of this class
	private Main() {}
	
	public static void main(String[] args) {
		// Set look & feel to native
		setNativeLAF();

		// Create the configuration module
		// FIXME set a correct filename, etc
		XmlConfigurationModule configurationModule = new XmlConfigurationModule("config.xml", "urn:blah");
		
		// Create the default modules
		List<Module> modules = new ArrayList<Module>();
		modules.add(configurationModule);
		modules.add(new CoreModule());
		modules.add(new UIModule());
		
		// Load plugin modules
		modules.addAll(createPluginModules(configurationModule));
		modules.add(loadModule("nl.queuemanager.activemq.ActiveMQModule"));
		
		// Now that the module list is complete, create the injector
		final Injector injector = Guice.createInjector(Stage.PRODUCTION, modules);
		
		// Invoke initializing the GUI on the EDT
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				// Set some platform properties before the UI really loads
				PlatformHelper helper = injector.getInstance(PlatformHelper.class);
				helper.setApplicationName("Message Manager");
				
				// Create the main application frame
				final JFrame frame = injector.getInstance(MMFrame.class);
				
				// Make the frame visible
				frame.setVisible(true);
				
				// Send the ApplicationInitializedEvent
				injector.getInstance(EventBus.class).post(new ApplicationInitializedEvent());
			}
		});
	}
	
	/**
	 * Load initial Injector to be able to read configuration for plugin loading.
	 * For some reason, Guice complains about things already being injected when
	 * child injectors are used. Until I find a solution for that, we dicard this
	 * injector and configuration object after use and use it only to retrieve the
	 * list of plugin modules to load.
	 */
	private static List<Module> createPluginModules(Module configurationModule) {
		Injector configInjector = Guice.createInjector(Stage.PRODUCTION, configurationModule);
		Configuration config = configInjector.getInstance(Configuration.class);
		
		String[] moduleNameList = config.getUserPref(Configuration.PREF_PLUGIN_MODULES, "").split(",");
		List<Module> modules = new ArrayList<Module>(moduleNameList.length);
		
		for(String moduleName: moduleNameList) {
			if(moduleName.length() == 0)
				continue;
			
			Module module = loadModule(moduleName);
			if(module != null && module instanceof Module) {
				modules.add(module);
			}
		}
		
		return modules;
	}

	private static Module loadModule(String moduleName) {
		try {
			return (Module) Class.forName(moduleName).newInstance();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		return null;
	}

	private static void setNativeLAF() { 
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			if(Boolean.TRUE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("awt.dynamicLayoutSupported")))
				Toolkit.getDefaultToolkit().setDynamicLayout(true);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	
}
