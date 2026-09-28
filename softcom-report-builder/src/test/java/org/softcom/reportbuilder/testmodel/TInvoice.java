package org.softcom.reportbuilder.testmodel;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

@Entity
@Table(name = "t_invoice")
public class TInvoice {
	@Id
	private Long id;
	@Temporal(TemporalType.TIMESTAMP)
	private Date invoiceDate;
	private boolean closed;
	@ManyToOne(optional = false)
	private TWarehouse warehouse;
	@ManyToOne(optional = false)
	private TCompany company;
	@ManyToOne
	private TCustomer customer;
	/** Mirrors generalWarehouse: lines are reached from the invoice (collection), used for the grain test. */
	@OneToMany(mappedBy = "invoice")
	private List<TInvoiceLine> lines = new ArrayList<>();

	public TInvoice() {
	}

	public TInvoice(Long id, Date invoiceDate, boolean closed, TWarehouse warehouse, TCompany company, TCustomer customer) {
		this.id = id;
		this.invoiceDate = invoiceDate;
		this.closed = closed;
		this.warehouse = warehouse;
		this.company = company;
		this.customer = customer;
	}
}
