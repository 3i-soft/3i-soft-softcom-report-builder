package org.softcom.reportbuilder.testmodel;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.Version;

/** For automatic discovery: a one-way collection, and attributes that must never be offered. */
@Entity
@Table(name = "t_order")
public class TOrder {
	@Id
	private Long id;
	@Temporal(TemporalType.DATE)
	private Date orderDate;
	@ManyToOne
	private TCustomer customer;
	/** lines cannot navigate back to the order: reachable only through this collection */
	@OneToMany
	@JoinColumn(name = "order_id")
	private List<TOrderLine> lines = new ArrayList<>();
	private String accessToken;
	@Lob
	private String notes;
	@Version
	private int version;

	public TOrder() {
	}

	public TOrder(Long id, Date orderDate, TCustomer customer) {
		this.id = id;
		this.orderDate = orderDate;
		this.customer = customer;
	}

	public List<TOrderLine> getLines() {
		return lines;
	}
}
