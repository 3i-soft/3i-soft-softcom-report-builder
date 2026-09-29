package org.softcom.reportbuilder.testmodel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Same simple name as the 3i-soft {@code @Code(codeKind)} annotation of code fields. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Code {
	String codeKind() default "";
}
