open module messagemanager.ui {
    exports nl.queuemanager.ui;
    exports nl.queuemanager.ui.settings;
    exports nl.queuemanager.ui.util;
    exports nl.queuemanager.ui.util.validation;
    exports nl.queuemanager.ui.task;
    exports nl.queuemanager.ui.message;
    exports nl.queuemanager.ui.about;

    requires messagemanager.jmsmessages;
    requires messagemanager.core;

    requires jakarta.jms.api;
    requires com.google.common;
    requires com.google.guice;
    requires com.google.guice.extensions.assistedinject;

    requires java.desktop;
    requires java.logging;
    requires jakarta.inject;
    requires org.fife.RSyntaxTextArea;
}