package org.softcom.reportbuilder.testmodel;

import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "t_item")
public class TItem {
	@Id
	private Long id;
	private String code;
	private String name;
	@Enumerated(EnumType.STRING)
	private TItemKind kind;
	private boolean active;
	/** a number on a related entity: summing it over invoice lines would count it once per line */
	private Integer shelfLifeDays;

	public TItem() {
	}

	public TItem(Long id, String code, String name, TItemKind kind, boolean active) {
		this.id = id;
		this.code = code;
		this.name = name;
		this.kind = kind;
		this.active = active;
	}

	public Long getId() {
		return id;
	}
}
