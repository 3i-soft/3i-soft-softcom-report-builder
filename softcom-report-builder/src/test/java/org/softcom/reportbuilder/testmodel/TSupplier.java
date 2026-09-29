package org.softcom.reportbuilder.testmodel;

import javax.persistence.Entity;
import javax.persistence.Table;

@Entity
@Table(name = "t_supplier")
public class TSupplier extends TBaseEntity {
	private String supplierName;

	public TSupplier() {
	}

	public TSupplier(double id, String supplierName) {
		setId(id);
		this.supplierName = supplierName;
	}
}
