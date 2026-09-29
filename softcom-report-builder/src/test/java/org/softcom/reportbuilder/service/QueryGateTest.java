package org.softcom.reportbuilder.service;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.softcom.reportbuilder.engine.ReportException;
import org.softcom.reportbuilder.spi.ReportBuilderConfig;

public class QueryGateTest {

	@Test
	public void aFullGateAsksTheUserToTryAgain() throws Exception {
		System.setProperty(ReportBuilderConfig.MAX_CONCURRENT_QUERIES, "1");
		QueryGate.waitSeconds = 1;
		final AtomicReference<String> outcome = new AtomicReference<>("not run");
		QueryGate.enter();
		try {
			Thread other = new Thread(() -> {
				try {
					QueryGate.enter();
					QueryGate.leave();
					outcome.set("entered");
				} catch (ReportException e) {
					outcome.set(e.getMessageKey());
				}
			});
			other.start();
			other.join(5000);
		} finally {
			QueryGate.leave();
		}
		assertEquals("the only slot was taken", "rb.error.busy", outcome.get());
		QueryGate.enter(); // free again
		QueryGate.leave();
	}
}
