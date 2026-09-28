package org.softcom.reportbuilder.service;

import java.io.Serializable;

import javax.enterprise.context.ApplicationScoped;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;

/**
 * Container-security implementation: user = remote user, permission = servlet
 * role. Replace it with a CDI alternative when the application checks
 * permissions another way (see {@link ReportSecurity}).
 */
@ApplicationScoped
public class DefaultReportSecurity implements ReportSecurity, Serializable {

	private static final long serialVersionUID = 1L;

	@Override
	public String getCurrentUser() {
		ExternalContext ec = externalContext();
		return ec == null ? null : ec.getRemoteUser();
	}

	@Override
	public boolean hasPermission(String permission) {
		ExternalContext ec = externalContext();
		return ec != null && permission != null && ec.isUserInRole(permission);
	}

	private static ExternalContext externalContext() {
		FacesContext fc = FacesContext.getCurrentInstance();
		return fc == null ? null : fc.getExternalContext();
	}
}
