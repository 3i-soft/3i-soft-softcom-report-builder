package org.softcom.reportbuilder.spi;

import java.util.Map;

/**
 * Optional CDI bean a host application may provide to hand request-specific
 * values (current company, branch...) to its forced filters through
 * {@link ReportQueryContext#getAttributes()}.
 */
public interface ReportAttributesProvider {

	Map<String, Object> getAttributes();
}
