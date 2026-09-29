package org.softcom.reportbuilder.testmodel;

import javax.persistence.Entity;
import javax.persistence.Table;

@Entity
@Table(name = "t_supplier")
public class TSupplier extends TBaseEntity {
	private String supplierName;
	/** like generalWarehouse's deletedInvoice: new reports start with "is not true" */
	private boolean deleted;
	/** a code of the application's codes service: reports show its name */
	@Code(codeKind = "CITY")
	private String cityCode;

	public TSupplier() {
	}

	public TSupplier(double id, String supplierName) {
		setId(id);
		this.supplierName = supplierName;
	}

	public TSupplier(double id, String supplierName, boolean deleted, String cityCode) {
		this(id, supplierName);
		this.deleted = deleted;
		this.cityCode = cityCode;
	}
}
