package org.softcom.reportbuilder.testmodel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Same simple name as the 3i-soft annotation the report builder reads labels from. */
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldViewConfiguration {
	String displayName() default "";
}
