package org.softcom.reportbuilder.testmodel;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

@Entity
@Table(name = "t_orderline")
public class TOrderLine {
	@Id
	private Long id;
	@ManyToOne(optional = false)
	private TItem item;
	@Column(precision = 16, scale = 4)
	private BigDecimal quantity;

	public TOrderLine() {
	}

	public TOrderLine(Long id, TItem item, String quantity) {
		this.id = id;
		this.item = item;
		this.quantity = new BigDecimal(quantity);
	}
}
