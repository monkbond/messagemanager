/**
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
package nl.queuemanager.core.configuration;

import nl.queuemanager.core.util.Credentials;
import nl.queuemanager.jms.JMSBroker;
import nl.queuemanager.jms.JMSTopic;

import java.util.List;

public interface CoreConfiguration {
	public static final String PREF_UNIQUE_ID = "uniqueId";
	public static final String PREF_BROWSE_DIRECTORY = "browseDirectory";
	public static final String PREF_SAVE_DIRECTORY = "saveDirectory";
	public static final String PREF_MAX_BUFFERED_MSG = "maxBufferedMessages";
	public static final String DEFAULT_MAX_BUFFERED_MSG = "50";
	public static final String PREF_AUTOREFRESH_INTERVAL = "autoRefreshInterval";
	public static final String DEFAULT_AUTOREFRESH_INTERVAL = "5000";
	public static final String PREF_BROKER_ALTERNATE_URL = "alternateUrl";
	public static final String PREF_PLUGIN_MODULES = "pluginModules";
	public static final String PREF_LAST_RUN_BUILD = "lastRunBuild";
	public static final String PREF_LAST_MOTD_NUMBER = "lastMotdNumber";
	public static final String PREF_LAST_MOTD_CHECK_TIME = "lastMotdCheckTime";
	
	public static final String PREF_LOOK_AND_FEEL = "lookAndFeel";
    public static final String PREF_LICENSE_KEY = "licenseKey";

	public static final String PREF_LAST_VERSION = "lastVersion";
	public static final String PREF_AUTOLOAD_PROFILE = "autoloadProfile";
	public static final String PREF_LAST_SELECTED_BROKER = "lastSelectedBroker";

    /**
	 * Return a unique identifier for this configuration
	 * @return
	 */
	public abstract String getUniqueId();
	
	/**
	 * Get a per-user preference value.
	 * 
	 * @param key
	 * @param def
	 * @return
	 */
	public abstract String getUserPref(String key, String def);

	/**
	 * Set a preference value for the user.
	 * 
	 * @param key
	 * @param value
	 */
	public abstract void setUserPref(String key, String value);

	/**
	 * List the brokers for which preferences are stored
	 * @return
	 */
	public abstract List<JMSBroker> listBrokers();
	
	/**
	 * Get the value of a single per-broker preference.
	 * 
	 * @param broker
	 * @param key
	 * @param def
	 * @return
	 */
	public abstract String getBrokerPref(JMSBroker broker, String key, String def);

	/**
	 * Set a single preference value for the specified broker.
	 * 
	 * @param broker
	 * @param key
	 * @param value
	 */
	public abstract void setBrokerPref(JMSBroker broker, String key, String value);

	/**
	 * Save the default credentials for the broker to the per-user configuration store
	 * 
	 * @param broker
	 * @param credentials
	 */
	public abstract void setBrokerCredentials(JMSBroker broker, Credentials credentials);

	/**
	 * Retrieve the stored credentials for this broker (if any). When there are no stored credentials,
	 * return null.
	 * 
	 * @param broker
	 * @return
	 */
	public abstract Credentials getBrokerCredentials(JMSBroker broker);
		
	/**
	 * Retrieve the stored list of topic subscribers for a broker
	 * 
	 * @param broker
	 * @return
	 */
	public abstract List<String> getTopicSubscriberNames(JMSBroker broker);

	/**
	 * Retrieve the stored list of topic publishers for a broker
	 * 
	 * @param broker
	 * @return
	 */
	public abstract List<String> getTopicPublisherNames(JMSBroker broker);

	/**
	 * Add a topic subscriber to the list for its broker
	 * 
	 * @param topic
	 */
	public abstract void addTopicSubscriber(JMSTopic topic);

	/**
	 * Add a topic publisher to the list for its broker
	 * 
	 * @param topic
	 */
	public abstract void addTopicPublisher(JMSTopic topic);

	/**
	 * Remove a topic publisher from the saved list for its associated broker.
	 * 
	 * @param topic
	 */
	public abstract void removeTopicPublisher(JMSTopic topic);
	
	/**
	 * Remove a topic subscriber from the saved list for its associated broker.
	 * 
	 * @param topic
	 */
	public void removeTopicSubscriber(JMSTopic topic);

}