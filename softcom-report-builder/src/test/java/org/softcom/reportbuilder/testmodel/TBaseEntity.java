package org.softcom.reportbuilder.testmodel;

import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;

/** Mirrors generalWarehouse's MainEntity: a primitive double id declared in a mapped superclass. */
@MappedSuperclass
public class TBaseEntity {
	@Id
	@Column(columnDefinition = "numeric")
	private double id;

	public double getId() {
		return id;
	}

	public void setId(double id) {
		this.id = id;
	}
}
