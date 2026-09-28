package org.softcom.reportbuilder.testmodel;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

@Entity
@Table(name = "t_warehouse")
public class TWarehouse {
	@Id
	private Long id;
	private String name;
	@ManyToOne(optional = false)
	private TCompany company;

	public TWarehouse() {
	}

	public TWarehouse(Long id, String name, TCompany company) {
		this.id = id;
		this.name = name;
		this.company = company;
	}

	public Long getId() {
		return id;
	}
}
