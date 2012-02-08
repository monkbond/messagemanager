/**
 * 
 */
package nl.queuemanager.core.util;

public class Credentials {
	private final String username;
	private final String password;
	
	public Credentials(String username, String password) {
		this.username = username;
		this.password = password;
	}
	
	public String toString() {
		return getUsername() + ":" + getPassword();
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}
}