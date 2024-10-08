package nl.queuemanager.app;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PluginDescriptor {

	private String name;
	private String description;
	private String moduleClass;
	private File file;
	private List<URL> classpath = new ArrayList<URL>();
	private String pluginType; // UI, ConnectivityProvider, etc. for the future we will have more types and then use Enum
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public String getModuleClassName() {
		return moduleClass;
	}
	public void setModuleClass(String moduleClass) {
		this.moduleClass = moduleClass;
	}
	public File getFile() {
		return file;
	}
	public void setFile(File file) {
		this.file = file;
	}
	public List<URL> getClasspath() {
		return classpath;
	}
	public void setClasspath(List<? extends URL> classpath) {
		ArrayList<URL> cp = new ArrayList<URL>();
		cp.addAll(classpath);
		this.classpath = Collections.unmodifiableList(cp);
	}

	public boolean isConnectivityProvider() {
		return "ConnectivityProvider".equals(pluginType);
	}

	public void setPluginType(String pluginType) {
		this.pluginType = pluginType;
	}

	public String getPluginType() {
		return pluginType;
	}
}
