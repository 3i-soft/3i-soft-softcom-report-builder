package org.softcom.reportbuilder.testmodel;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "t_company")
public class TCompany {
	@Id
	private Long id;
	@FieldInfo(label = "Company name")
	@FieldViewConfiguration(displayName = "اسم الشركة")
	private String name;

	public TCompany() {
	}

	public TCompany(Long id, String name) {
		this.id = id;
		this.name = name;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}
}
