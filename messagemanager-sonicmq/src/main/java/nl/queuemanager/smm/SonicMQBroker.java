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

import nl.queuemanager.jms.JMSBroker;

import javax.management.ObjectName;

class SonicMQBroker implements Comparable<JMSBroker>, JMSBroker {
	public static enum ROLE {
		PRIMARY,
		BACKUP
	}

	private final ObjectName objectName;
	private final String brokerName;
	private final String configStorageName;
	private final String containerHost;
	private final ROLE role;

	/** Resolves this broker's connection URL, quietly - see {@link #getPreferenceKey()}. */
	interface UrlResolver {
		void resolve(SonicMQBroker broker);
	}

	// Resolved lazily on first connect; enumerating acceptor configuration for
	// every broker makes listing large domains take minutes.
	private volatile String brokerURL;
	private final UrlResolver urlResolver;

	// Resolution is attempted at most once. Besides avoiding a retry on every lookup, this
	// breaks a cycle: resolving falls back to the configured alternate URL when the broker's
	// acceptor cannot be read, and reading that preference asks for this key again.
	private volatile boolean urlResolutionAttempted;

	// The name the broker carries in the DOMAIN CONFIGURATION, which is what earlier versions
	// built the preference key from. It comes from the same configuration bean as the URL and
	// is resolved with it. Kept apart from brokerName because that one identifies this object
	// (equals/hashCode) and is a key in maps of live connections and of task queues - it must
	// never change once the broker has been handed out.
	private volatile String configuredName;

	public SonicMQBroker(ObjectName objectName, String brokerName, String configStorageName, String containerHost, ROLE role) {
		this(objectName, brokerName, configStorageName, containerHost, role, null);
	}

	public SonicMQBroker(ObjectName objectName, String brokerName, String configStorageName, String containerHost,
			ROLE role, UrlResolver urlResolver) {
		this.objectName        = objectName;
		this.brokerName        = brokerName;
		this.configStorageName = configStorageName;
		this.containerHost     = containerHost;
		this.role              = role;
		this.urlResolver       = urlResolver;
	}

	private String sanitizeBrokerUrl(String connectionUrl) {
		// Removes #ONLY from the URL if present
		return connectionUrl.replace("#ONLY", "");
	}

	public String getBrokerName() {
		return brokerName;
	}

	/**
	 * The JMS connection URL for this broker, or null when it has not been
	 * resolved yet.
	 */
	public String getBrokerURL() {
		return brokerURL;
	}

	public void setBrokerURL(String connectionUrl) {
		this.brokerURL = sanitizeBrokerUrl(connectionUrl);
	}

	/**
	 * The broker's name as configured in the domain - see {@link #configuredName}. Set while
	 * resolving the URL, from the same configuration bean.
	 */
	public void setConfiguredName(String configuredName) {
		this.configuredName = configuredName;
	}

	public String getConfigStorageName() {
		return configStorageName;
	}

	public String getContainerHost() {
		return containerHost;
	}

	public ObjectName getObjectName() {
		return objectName;
	}

	public ROLE getRole() {
		return role;
	}

	/**
	 * {@code <brokerName> (<connectionUrl>)} - the format this application has always stored
	 * per-broker settings under, so existing configurations keep working.
	 * <p>
	 * The connection URL is the only part of a broker's identity that is reliably unique.
	 * Two environments are routinely clones of one another: same domain name, same container
	 * names, same broker names, and they may even share a machine and differ only by port.
	 * That defeats the broker name, the container host and the runtime ObjectName alike - but
	 * not the acceptor URL, which carries the port.
	 * <p>
	 * The URL is resolved on demand here because settings are read before the broker is
	 * connected (topic lists when it is selected, credentials just before connecting). The
	 * result is cached on this instance, so this costs one resolution for each broker the
	 * user actually opens - never for the rest of the list, which is what keeps enumerating
	 * a large domain fast. {@link #toString()} deliberately does NOT resolve: it is called
	 * for every broker in the list.
	 */
	@Override
	public String getPreferenceKey() {
		String url = brokerURL;

		if(url == null && urlResolver != null && !urlResolutionAttempted) {
			urlResolutionAttempted = true;
			urlResolver.resolve(this);
			url = brokerURL;
		}

		final String name = configuredName != null ? configuredName : brokerName;

		// A broker whose URL cannot be resolved cannot be connected to either; fall back to
		// something that is at least stable rather than to the bare (colliding) name.
		return url != null
				? name + " (" + url + ")"
				: name + " (" + containerHost + ")";
	}

	@Override
	public String toString() {
		// Purely the display label - see getPreferenceKey() for what settings are stored
		// under. The container host is shown because a domain may run brokers of the same
		// name on several machines; it is known from the collective state, while the broker
		// URL is only resolved once a broker is selected.
		return containerHost == null || containerHost.length() == 0
				? getBrokerName()
				: getBrokerName() + " (" + containerHost + ")";
	}

	public int compareTo(JMSBroker other) {
		return toString().compareTo(other.toString());
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((brokerName == null) ? 0 : brokerName.hashCode());
		result = PRIME * result + ((objectName == null) ? 0 : objectName.hashCode());
		result = PRIME * result + ((role == null) ? 0 : role.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final SonicMQBroker other = (SonicMQBroker) obj;
		if (brokerName == null) {
			if (other.brokerName != null)
				return false;
		} else if (!brokerName.equals(other.brokerName))
			return false;
		if (objectName == null) {
			if (other.objectName != null)
				return false;
		} else if (!objectName.equals(other.objectName))
			return false;
		if (role == null) {
			if (other.role != null)
				return false;
		} else if (!role.equals(other.role))
			return false;
		return true;
	}
}
