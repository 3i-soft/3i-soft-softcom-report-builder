package org.softcom.reportbuilder.testmodel;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

@Entity
@Table(name = "t_invoiceline")
public class TInvoiceLine {
	@Id
	private Long id;
	@ManyToOne(optional = false)
	private TInvoice invoice;
	@ManyToOne(optional = false)
	private TItem item;
	@Column(precision = 16, scale = 4)
	private BigDecimal quantity;
	@Column(precision = 16, scale = 4)
	private BigDecimal price;
	private int lineNo;

	public TInvoiceLine() {
	}

	public TInvoiceLine(Long id, TInvoice invoice, TItem item, String quantity, String price, int lineNo) {
		this.id = id;
		this.invoice = invoice;
		this.item = item;
		this.quantity = new BigDecimal(quantity);
		this.price = new BigDecimal(price);
		this.lineNo = lineNo;
	}
}
