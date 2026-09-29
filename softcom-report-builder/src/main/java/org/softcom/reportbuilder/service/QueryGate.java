package org.softcom.reportbuilder.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.softcom.reportbuilder.engine.ReportException;
import org.softcom.reportbuilder.spi.ReportBuilderConfig;

/**
 * Limits how many report queries run at the same time in the application
 * ({@link ReportBuilderConfig#MAX_CONCURRENT_QUERIES}), so reports cannot take
 * the database away from the rest of the system. A report waits a little for
 * a free slot, then the user is asked to try again.
 */
final class QueryGate {

	/** How long a report waits for a free slot (not final: tests shorten it). */
	static long waitSeconds = 15;

	private static volatile Semaphore slots;

	private QueryGate() {
	}

	static void enter() {
		try {
			if (!slots().tryAcquire(waitSeconds, TimeUnit.SECONDS))
				throw new ReportException("rb.error.busy");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ReportException(e, "rb.error.busy");
		}
	}

	static void leave() {
		slots().release();
	}

	private static Semaphore slots() {
		Semaphore s = slots;
		if (s == null) {
			synchronized (QueryGate.class) {
				s = slots;
				if (s == null) {
					s = new Semaphore(ReportBuilderConfig.getInt(ReportBuilderConfig.MAX_CONCURRENT_QUERIES, 6, 1, 1000), true);
					slots = s;
				}
			}
		}
		return s;
	}
}
