package nl.queuemanager.jms;
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


/**
 * This interface represents a JMS broker. It does not impose any restrictions on 
 * implementations other than expecting the toString() method to return a suitable
 * name for display purposes.
 * 
 * @author Gerco Dries (gdr@progaia-rs.nl)
 *
 */
public interface JMSBroker extends Comparable<JMSBroker> {

	/**
	 * The key under which this broker's settings (alternate URL, credentials, saved topic
	 * subscribers and publishers) are stored in the configuration.
	 * <p>
	 * It must identify the broker <em>uniquely and permanently</em>, which is a different
	 * job from {@link #toString()}: a display name is chosen to read well, and two brokers
	 * of the same name in a test and a production domain would share it. When they share a
	 * storage key they also share the alternate URL saved against it, which silently points
	 * one environment's connection at the other.
	 * <p>
	 * Defaults to {@link #toString()} for implementations whose display name happens to be
	 * a stable unique identity; override where it is not.
	 *
	 * @return a stable, unique key for this broker
	 */
	default String getPreferenceKey() {
		return toString();
	}

}
