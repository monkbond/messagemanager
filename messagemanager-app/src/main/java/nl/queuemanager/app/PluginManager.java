package nl.queuemanager.app;

import com.google.common.io.Resources;
import com.google.inject.Module;
import nl.queuemanager.Profile;
import nl.queuemanager.core.DebugProperty;
import nl.queuemanager.core.platform.PlatformHelper;
import nl.queuemanager.core.task.TaskExecutor;
import nl.queuemanager.core.util.EnumerationIterator;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Singleton
public class PluginManager {
	private final Logger logger = Logger.getLogger(getClass().getName());
	private final TaskExecutor worker;
	private final ProfileManager profileManager;
	
	private final File pluginsFolder;
	private Map<String, PluginDescriptor> plugins = new HashMap<>();
	private ClassLoader pluginClassloader;
	private ModuleLayer pluginModuleLayer;
	
	@Inject
	public PluginManager(PlatformHelper platform, ProfileManager profileManager, TaskExecutor worker) {
		this.worker = worker;
		this.profileManager = profileManager;

		pluginsFolder = new File(new File(platform.getDataFolder(), "plugins"), Version.VERSION);
		installProvidedPluginsFromResources(pluginsFolder);
		plugins.putAll(findInstalledPlugins(pluginsFolder));

		// load bootstrapped plugins
		final File bootStrapPluginsFolder = Path.of("", "plugins").toAbsolutePath().toFile();
		installProvidedPluginsFromResources(bootStrapPluginsFolder);
		plugins.putAll(findInstalledPlugins(bootStrapPluginsFolder));

		ServiceLoader<Module> serviceLoader = ServiceLoader.load(Module.class);
		serviceLoader.stream()
				.map(this::toPlugin)
				.forEach(pl -> plugins.put(pl.getModuleClassName(), pl));
		serviceLoader.stream()
				.map(this::toProfile)
				.forEach(profileManager::putProfileIfNotExist);
	}

	public List<PluginDescriptor> getInstalledPlugins() {
		return new ArrayList<>(plugins.values());
	}

	/**
	 * Find and install any provided-plugins that are on the classpath. This is to allow plugins to be 
	 * provided in a JNLP file (packed inside a jar) and directly installed. There isn't going to be a 
	 * download option at first so all plugins will have to be installed this way.
	 */
	private void installProvidedPluginsFromResources(File pluginsFolder) {
		final String PROVIDED_PLUGINS_FILE = "MessageManager/provided-plugins";

		try {
			Enumeration<URL> urls = getClass().getClassLoader().getResources(PROVIDED_PLUGINS_FILE);
			if(!urls.hasMoreElements()) {
				urls = ClassLoader.getSystemResources(PROVIDED_PLUGINS_FILE);
			}
			if(!urls.hasMoreElements()) {
				URL url = getClass().getResource(PROVIDED_PLUGINS_FILE);
				if(url != null) {
					urls = new Vector<URL>(Collections.singleton(url)).elements();
				}
			}
			for(URL url: EnumerationIterator.of(urls)) {
				try {
					installProvidedPluginsFromResource(pluginsFolder, url);
				} catch (IOException e) {
					logger.log(Level.WARNING, String.format("Cannot install plugins! Unable to read %s", url.toString()), e);
				}
			}
		} catch (IOException e) {
			logger.log(Level.WARNING, "Cannot install plugins! Exception while finding provided-plugins file", e);
		}
	}
	
	private void installProvidedPluginsFromResource(File pluginsFolder, URL providedPluginsList) throws IOException {
		logger.info(String.format("Installing plugins from %s", providedPluginsList.toString()));
		
		// Open the file and read the names of the plugin jars
		List<String> jarnames = Resources.readLines(providedPluginsList, Charset.defaultCharset());
		for(String jar: jarnames) {
			try {
				installProvidedPlugin(pluginsFolder, jar);
			} catch (IOException e) {
				logger.log(Level.WARNING, String.format("Cannot install plugin %s", jar), e);
			}
		}
	}
	
	private void installProvidedPlugin(File pluginsFolder, String jarName) throws IOException {
		URL res = Resources.getResource(jarName);
		if(res != null) {
			logger.info(String.format("Found plugin %s", res.toString()));
			pluginsFolder.mkdirs(); // Ensure the directory exists
			File pluginFile = new File(pluginsFolder, getLastPathComponent(res.getPath()));
			if(!pluginFile.exists() || DebugProperty.forceInstallPlugins.isEnabled()) { // Only install plugin if it doesn't exist yet
				logger.info(String.format("Installing plugin %s", res.toString()));
				FileOutputStream fos = new FileOutputStream(pluginFile);
				Resources.copy(res, fos);
			}
		}
	}
	
	public Map<String, PluginDescriptor> findInstalledPlugins(File pluginsFolder) {
		Map<String, PluginDescriptor> ret = new HashMap<String, PluginDescriptor>();
		
		pluginsFolder.mkdirs(); // Ensure the directory exists
		logger.info("Looking for plugins in " + pluginsFolder.getAbsolutePath());
		
		// Find all plugin files
		final File[] pluginFiles = pluginsFolder.listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.endsWith(".jar");
			}
		});
		
		if(pluginFiles == null) {
			logger.warning("No plugins found");
			return ret;
		}
		
		// Now read their plugin descriptors to make sure we only consider actual plugin jars
		for(final File pluginFile: pluginFiles) {
			logger.fine(String.format("Reading potential plugin file %s", pluginFile.getAbsolutePath()));
			try(ZipFile pluginZip = new ZipFile(pluginFile)) {
				
				for(ZipEntry entry: EnumerationIterator.of(pluginZip.entries())) {
					// Normalize directory separators in entry name
					String name = entry.getName().replace('\\', '/');
					
					// If entry is a plugin descriptor, read it
					// if multiple plugins are loaded into the same module layer then the common resource structure is causing issues.
					// therefore we in addition to "MessageManager/plugins/" we also allow <package>/plugins/
					if((name.startsWith("MessageManager/plugins/") || name.contains("/mm/plugins/")) && name.endsWith(".xml")) try (
						InputStream descriptorStream = ZipUtil.openStreamForZipEntry(pluginZip, entry))
					{
						PluginDescriptor descriptor = readDescriptor(pluginFile, descriptorStream);
						if(descriptor != null) {
							logger.fine(String.format("Found plugin: %s (%s) in file %s", descriptor.getName(), descriptor.getModuleClassName(), pluginFile.getName()));
							ret.put(descriptor.getModuleClassName(), descriptor);
						}
					}
					
					// If entry is a profile descriptor, read that
					if(name.startsWith("MessageManager/profiles/") && name.endsWith(".xml")) try (
						InputStream stream = ZipUtil.openStreamForZipEntry(pluginZip, entry))
					{
						Profile profile = profileManager.readDescriptor(stream, pluginZip);
						if(profile != null) {
							logger.fine(String.format("Found profile: %s in file %s", profile.getName(), pluginFile.getName()));
							profileManager.putProfileIfNotExist(profile, true);
						}
					}
				}
			} catch (IOException e) {
				logger.log(Level.WARNING, "Unable to read plugin descriptor for " + pluginFile.getAbsolutePath(), e);
			}
		}
		
		return ret;
	}
	
	
	private String getLastPathComponent(String path) {
		return path.substring(path.lastIndexOf('/'));
	}
	
	private PluginDescriptor readDescriptor(File pluginFile, InputStream stream) {
		if(stream == null) {
			return null;
		}
		
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.parse(new InputSource(stream));
			
			XPath xpath = XPathFactory.newInstance().newXPath();
			
			PluginDescriptor descriptor = new PluginDescriptor();
			descriptor.setName(xpath.evaluate("/plugin/name", doc));
			descriptor.setDescription(xpath.evaluate("/plugin/description", doc));
			descriptor.setModuleClass(xpath.evaluate("/plugin/moduleClass", doc));
			String pluginType = (String) xpath.evaluate("/plugin/pluginType", doc, XPathConstants.STRING);
			descriptor.setPluginType(pluginType.isEmpty()?"ConnectivityProvider":pluginType);
			// for plugins that have no profile, i.e. UI plugins we need to load the classpath as well
			NodeList classpathEntries = (NodeList) xpath.evaluate("/plugin/classpath/entry", doc, XPathConstants.NODESET);
			List<URL> classpathList = new ArrayList<>();
			for (int i = 0; i < classpathEntries.getLength(); i++) {
				Node entryNode = classpathEntries.item(i);
				String entryValue = entryNode.getTextContent().trim();
				classpathList.add(new URL(entryValue));
			}

			// Now classpathList contains all the entry values
			descriptor.setClasspath(classpathList);

			descriptor.setFile(pluginFile);
			return descriptor;
		} catch (ParserConfigurationException | SAXException | IOException | XPathExpressionException e) {
			logger.log(Level.WARNING, "Unable to read plugin descriptor from " + pluginFile.getAbsolutePath(), e);
		}
		
		return null;
	}
	
	public PluginDescriptor getPluginByClassName(String classname) {
		if(plugins.isEmpty()) {
			getInstalledPlugins();
		}

		return plugins.get(classname);
	}

	public Set<String> getPluginClassNamesByType(String type) {
		if(plugins.isEmpty()) {
			getInstalledPlugins();
		}

		Set<String> classnames = new HashSet<>();
		for(PluginDescriptor plugin: plugins.values()) {
			if(plugin.getPluginType().equals(type)) {
				classnames.add(plugin.getModuleClassName());
			}
		}
		return classnames;
	}
	
	public PluginDescriptor downloadPluginByClassname(String classname) throws PluginManagerException {
		throw new PluginManagerException("Plugin " + classname + " does not exist!");
	}

	public List<Module> loadPluginModules(Collection<? extends PluginDescriptor> plugins, List<URL> classpath) throws PluginManagerException {

		// only reset if classpath is null, we use this to detect non profile plugins
		if(pluginClassloader != null && classpath != null) {
			// Unload the existing plugins so we can try again
			pluginClassloader = null;
			pluginModuleLayer = null;
			worker.setContextClassLoader(null);
			Thread.currentThread().setContextClassLoader(null);
		}
		
		try {
			// setup the classpath for the plugins (usually only one plugin)
			List<URL> urls = new ArrayList<URL>();
			for(PluginDescriptor plugin: plugins) {
				Optional.of(plugin)
						.map(PluginDescriptor::getFile)
						.map(File::toURI)
						.map(ThrowingFunction.wrap(URI::toURL))
						.ifPresent(urls::add);
				urls.addAll(plugin.getClasspath());
			}

			if(classpath != null) {
				urls.addAll(classpath);
			}

			// Create the classloader for the plugins, using the "current" classloader as a parent
			//final Class parentClass = getClass();
			final ClassLoader parentClassLoader;
			final ModuleLayer parentLayer;
			if(classpath != null) {
				// profile plugin
				parentClassLoader = getClass().getClassLoader();
				parentLayer =getClass().getModule().getLayer();
			}
			else{
				// non profile plugin, use the profile plugin classloader as parent
				//parentClassLoader = pluginClassloader;
				//parentLayer = pluginModuleLayer;
				parentClassLoader = getClass().getClassLoader();
				parentLayer =getClass().getModule().getLayer();
			}

			final ModuleLayer moduleLayer = PluginModuleHelper.createPluginModuleLayer(urls, parentLayer, parentClassLoader);
			final ClassLoader classLoader = PluginModuleHelper.findFirstModuleClassLoader(moduleLayer);

			// Set the ClassLoader on the worker and the current thread to make sure any class loading
			// magic done by the plugins or any dependent classes (such as trying to use the context
			// class loader directly) will hopefully work.
			//TODO: not sure if this is still needed
			if(classpath != null) {
				worker.setContextClassLoader(classLoader);
				Thread.currentThread().setContextClassLoader(classLoader);
			}
			else{
				// this is not a profile plugin
				Thread.currentThread().setContextClassLoader(classLoader);
			}

			final List<com.google.inject.Module> result = new ArrayList<>();
			// Load the jars for the plugins using service loader using our Module Layer
			ServiceLoader<Module> slPlugins = ServiceLoader.load(moduleLayer, Module.class);
			for(Module ext : slPlugins) {
				result.add(ext);// add each found plugin module
			}

			if(classpath != null){
				pluginClassloader = classLoader;
				pluginModuleLayer = moduleLayer;
			}
			else{
				// this is not a profile plugin, reset the context classloader to the profile plugin classloader
				Thread.currentThread().setContextClassLoader(pluginClassloader);
			}

			return result;
		}
		catch (Exception e) {
			throw new PluginManagerException("Unable to load plugin modules", e);
		}
	}
	private PluginDescriptor toPlugin(ServiceLoader.Provider<Module> provider) {
		PluginDescriptor desc = new PluginDescriptor();
		desc.setName("Auto " + provider.type().getSimpleName());
		desc.setModuleClass(provider.type().getName());
		desc.setDescription("Auto-generated plugin descriptor");
		return desc;
	}

	private Profile toProfile(ServiceLoader.Provider<Module> provider) {
		Profile profile = new Profile();
		profile.setName(provider.type().getSimpleName());
		profile.setPlugins(Collections.singletonList(provider.type().getName()));
		profile.setJars(Collections.emptyList());
		return profile;
	}

}
