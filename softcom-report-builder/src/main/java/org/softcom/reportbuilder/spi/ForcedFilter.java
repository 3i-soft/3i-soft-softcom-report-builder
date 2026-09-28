package org.softcom.reportbuilder.spi;

import java.io.Serializable;
import java.util.List;

import javax.persistence.criteria.Predicate;

/**
 * Conditions that are always added to every query of a data source (tenant,
 * cancelled/deleted flags, document types...). They are combined with AND
 * around the user's own conditions, so a user OR can never widen them.
 */
@FunctionalInterface
public interface ForcedFilter extends Serializable {

	List<Predicate> build(ReportQueryContext context);
}
