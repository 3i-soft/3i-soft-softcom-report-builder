package org.softcom.reportbuilder.testmodel;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "t_customer")
public class TCustomer {
	@Id
	private Long id;
	private String name;

	public TCustomer() {
	}

	public TCustomer(Long id, String name) {
		this.id = id;
		this.name = name;
	}
}
