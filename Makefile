default:

make-app:
	mvn clean package -pl messagemanager-app -am

run-app:
	java    -Dmm.forceInstallPlugins=true \
		-Dmm.forceMotdMessage=true \
		-Ddeveloper=true \
		-jar messagemanager-app/target/messagemanager-app-3.1-SNAPSHOT-jar-with-dependencies.jar

build-ws-app:
	mvn -Pcodesign -Dsubdir=v3/nightly/ -Dsuffix=NIGHTLY clean package -pl ws-app -am
	 
run-ws-app:
	 javaws -J-Ddeveloper=true \
		-J-Dmm.forceInstallPlugins=true \
		-J-Djava.util.logging.config.file=messagemanager-app/logging.properties \
		http://queuemanager.nl/v3/nightly/app/MessageManager.jnlp

run-ws-76:
	 javaws -J-Ddeveloper=true \
		-J-Dmm.forceInstallPlugins=true \
		-J-Djava.util.logging.config.file=messagemanager-app/logging.properties \
		http://queuemanager.nl/v3/nightly/7.6/SonicMessageManager.jnlp
	 
upload-ws-app:
	scp ws-app/target/jnlp/* neon:domains/queuemanager.nl/public_html/v3/nightly/app/

clean-plugins:
	rm -rf ~/Library/Application\ Support/MessageManager/plugins/

clean-jws:
	javaws -uninstall
	javaws -clearcache

clean-all: clean-plugins clean-jws
	mvn clean

run: clean-all build-ws-app upload-ws-app run-ws-app

