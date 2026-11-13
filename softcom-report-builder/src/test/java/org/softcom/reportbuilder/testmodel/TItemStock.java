package org.softcom.reportbuilder.testmodel;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Like generalWarehouse's ItemCalculatedFields as seen from the invoice lines:
 * one row per item and warehouse, kept as plain numbers ({@code item_id},
 * {@code warehouse_id}) with no relation to the lines. Reports link it by
 * those columns.
 */
@Entity
@Table(name = "t_itemstock")
public class TItemStock {

	@Id
	private Long id;
	private double item_id;
	private double warehouse_id;
	@Column(precision = 16, scale = 4)
	private BigDecimal available;

	public TItemStock() {
	}

	public TItemStock(Long id, double itemId, double warehouseId, String available) {
		this.id = id;
		this.item_id = itemId;
		this.warehouse_id = warehouseId;
		this.available = new BigDecimal(available);
	}

	public Long getId() {
		return id;
	}

	public double getItem_id() {
		return item_id;
	}

	public double getWarehouse_id() {
		return warehouse_id;
	}

	public BigDecimal getAvailable() {
		return available;
	}
}
