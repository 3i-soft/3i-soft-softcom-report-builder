package org.softcom.reportbuilder.spi;

import java.io.Serializable;

import javax.persistence.criteria.Expression;

/**
 * Builds the value of a computed field, e.g. stock value:
 * {@code ctx -> ctx.getCriteriaBuilder().prod(ctx.path("purchacePrice"), ctx.path("currentQuantity"))}.
 */
@FunctionalInterface
public interface FieldExpression extends Serializable {

	Expression<?> build(ReportQueryContext context);
}
