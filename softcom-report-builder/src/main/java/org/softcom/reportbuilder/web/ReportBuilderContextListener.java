package org.softcom.reportbuilder.web;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.softcom.reportbuilder.spi.ReportBuilderConfig;

/**
 * Lets {@link ReportBuilderConfig} read the application's context parameters
 * outside a JSF request too (registered automatically from the library jar).
 */
@WebListener
public class ReportBuilderContextListener implements ServletContextListener {

	@Override
	public void contextInitialized(ServletContextEvent event) {
		ReportBuilderConfig.setServletContext(event.getServletContext());
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		ReportBuilderConfig.setServletContext(null);
	}
}
