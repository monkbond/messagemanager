package nl.queuemanager.core;

public class Version {

	public static synchronized String getVersion() {
		return VERSION;
	}

	public static final String VERSION = "4.0.5";
	//public static final String SUFFIX = "${suffix}";
	public static final String BUILD_TIMESTAMP = "Fri Aug 09 15:32:46 CEST 2024";
	public static final String BUILD_ID = "4.0.5";
}
