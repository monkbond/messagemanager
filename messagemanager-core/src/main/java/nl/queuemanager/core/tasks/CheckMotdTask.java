package nl.queuemanager.core.tasks;

import com.google.common.eventbus.EventBus;
import com.google.inject.assistedinject.Assisted;
import nl.queuemanager.core.DebugProperty;
import nl.queuemanager.core.Version;
import nl.queuemanager.core.configuration.CoreConfiguration;
import nl.queuemanager.core.task.BackgroundTask;
import nl.queuemanager.core.util.DNSUtil;
import nl.queuemanager.core.util.ReleasePropertiesEvent;

import jakarta.inject.Inject;
import java.util.Calendar;
import java.util.logging.Logger;

/**
 * <p>
 * Retrieve MOTD from home base. The MOTD info is encoded in the TXT DNS records of 
 * smm.queuemanager.nl and *.motd.queuemanager.nl. The reasons for this are as follows:
 * </p><p>
 * <ol>
 * <li>We can easily get this information from behind proxy servers;</li>
 * <li>If we are behind a proxy, there should be no authentication dialog popping up;</li>
 * <li>The information will be cached for free by any corporate/ISP DNS servers.</li>
 * </ol>
 * </p>
 **/
public class CheckMotdTask extends BackgroundTask {
	private final Logger log = Logger.getLogger(getClass().getName());
	private final CoreConfiguration config;
	private final String uniqueId;
	private final String hostname;
	
	@Inject
	public CheckMotdTask(CoreConfiguration config, EventBus eventBus, @Assisted("uniqueId") String uniqueId, @Assisted("hostname") String hostname) {
		super(null, eventBus);
		this.config = config;
		this.uniqueId = uniqueId;
		this.hostname = hostname;
	}

	@Override
	public void execute() throws Exception {
		if(DebugProperty.forceMotdMessage.isEnabled()) {
			eventBus.post(new ReleasePropertiesEvent(ReleasePropertiesEvent.EVENT.MOTD_FOUND, this, "MOTD message forced by system property"));
		}
		
		if(!shouldCheckForMOTD()) {
			return;
		}
				
		// If the MOTD# is higher than we previously had stored, show the MOTD.
		// Get the last MOTD number we showed
		int lastMotdNumber = getLastMotdNumber();
		
		// Get the latest MOTD number from the server
		int latestMotdNumber = getLatestMotdNumber();

		// only show MOTDs 100 and higher, the lower ones are used to inform v2.x and v3.x customers about the migration
		if(latestMotdNumber > 100 && latestMotdNumber > lastMotdNumber) {
			String motd = DNSUtil.getFirstTxtRecord(latestMotdNumber + ".motd." + hostname);
			log.info(String.format("There is a new MOTD (%d > %d), showing MOTD: %s", latestMotdNumber, lastMotdNumber, motd));
			eventBus.post(new ReleasePropertiesEvent(ReleasePropertiesEvent.EVENT.MOTD_FOUND, this, motd));
			config.setUserPref(CoreConfiguration.PREF_LAST_MOTD_NUMBER, Integer.toString(latestMotdNumber));
		}
		
		// Store the last check run time
		config.setUserPref(CoreConfiguration.PREF_LAST_MOTD_CHECK_TIME, 
				Long.toString(Calendar.getInstance().getTimeInMillis()));
	}


	/**
	 * Returns a DNS-safe string combining OS name and architecture.
	 * Replaces spaces and invalid characters with hyphens.
	 * Only allows a-z, A-Z, 0-9, and single hyphens.
	 */
	public static String getDnsSafeSystemInfo() {
		String osName = System.getProperty("os.name");
		String osArch = System.getProperty("os.arch");

		// Combine OS and architecture
		String combined = osName + "-" + osArch;

		// Convert to lowercase
		combined = combined.toLowerCase();

		// Replace any character that's not a-z, 0-9 with a hyphen
		combined = combined.replaceAll("[^a-z0-9]+", "");

		// Remove leading or trailing hyphens
		combined = combined.replaceAll("^-+|-+$", "");

		// Replace multiple consecutive hyphens
		combined = combined.replaceAll("-+", "");

		return combined;
	}

	private int getLatestMotdNumber() {
		int latestMotdNumber = 0;
		try {
			/* This information is used to identify if certain operating systems and versions are still in use*/
			String info = getDnsSafeSystemInfo();
			String version = Version.getVersion().replace(".","");
			String checkIn = info+"-"+version+"-"+uniqueId;
			latestMotdNumber = Integer.parseInt(DNSUtil.getFirstTxtRecord(checkIn + "." + hostname));
		} catch (NumberFormatException e) {
			log.warning("Could not parse latest MOTD number. Assuming 0");
		}
		return latestMotdNumber;
	}

	private int getLastMotdNumber() {
		int lastMotdNumber = 0;
		try {
			lastMotdNumber = Integer.parseInt(config.getUserPref(CoreConfiguration.PREF_LAST_MOTD_NUMBER, "0"));
		} catch (NumberFormatException e) {
			log.warning("Could not parse last MOTD number. Assuming 0");
		}
		return lastMotdNumber;
	}
	
	private boolean shouldCheckForMOTD() {
		// If the check is forced, always do it
		if(DebugProperty.forceMotdCheck.isEnabled()) {
			log.warning("MOTD check forced by system property");
			return true;
		}

		// If we haven't run the check yet today, run it
		Calendar lastRunTimestamp = Calendar.getInstance();
		lastRunTimestamp.setTimeInMillis(Long.parseLong(config.getUserPref(CoreConfiguration.PREF_LAST_MOTD_CHECK_TIME, "0")));
		if((lastRunTimestamp.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR))
		|| (lastRunTimestamp.get(Calendar.YEAR) != Calendar.getInstance().get(Calendar.YEAR))) {
			log.info("Last check was not today, run MOTD check");
			return true;
		}

		return false;
	}
	
}