package org.softcom.reportbuilder.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;
import org.softcom.reportbuilder.testmodel.TInvoice;
import org.softcom.reportbuilder.testmodel.TItem;

public class ReportCatalogTest {

	private static ReportDataSource auto(String key, Class<?> root) {
		return new ReportDataSource(key, root).add(ReportField.of("id", FieldType.LONG, null, "Id")).automatic(true);
	}

	@Test
	public void entitiesPublishedWithForcedFiltersAreNotAlsoOfferedAutomatically() {
		ReportDataSource restricted = new ReportDataSource("h.invoices", TInvoice.class)
				.add(ReportField.of("id", FieldType.LONG, null, "Id"))
				.forcedFilter(ctx -> Arrays.asList(ctx.getCriteriaBuilder().isTrue(ctx.<Boolean>path("closed"))));
		List<ReportDataSource> handWritten = Collections.singletonList(restricted);
		assertTrue("would bypass the forced filter", ReportCatalog.restrictedByHandWritten(auto("auto.TInvoice", TInvoice.class), handWritten));
		assertFalse(ReportCatalog.restrictedByHandWritten(auto("auto.TItem", TItem.class), handWritten));
		ReportDataSource open = new ReportDataSource("h.invoices", TInvoice.class).add(ReportField.of("id", FieldType.LONG, null, "Id"));
		assertFalse("no forced filter, nothing to bypass",
				ReportCatalog.restrictedByHandWritten(auto("auto.TInvoice", TInvoice.class), Collections.singletonList(open)));
	}
}
