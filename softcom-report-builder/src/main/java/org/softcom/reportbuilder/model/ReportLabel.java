package org.softcom.reportbuilder.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/**
 * A display name entered on the labels page, for a table ({@code Invoice}), a
 * field ({@code Invoice.totalPrice}, or {@code MainEntity.creationDate} for
 * every table inheriting it) or a value ({@code InvoiceStatus.CLOSED}). It
 * wins over every other source of labels.
 */
@Entity
@Table(name = "rb_label")
@NamedQueries({ @NamedQuery(name = ReportLabel.FIND_ALL, query = "SELECT l FROM ReportLabel l") })
public class ReportLabel implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String FIND_ALL = "ReportLabel.findAll";

	@Id
	@Column(name = "label_key", length = 300)
	private String key;

	@Column(name = "label_ar", length = 300)
	private String labelAr;

	@Column(name = "label_en", length = 300)
	private String labelEn;

	@Column(name = "updated_by", length = 100)
	private String updatedBy;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "updated_date")
	private Date updatedDate;

	public ReportLabel() {
	}

	public ReportLabel(String key) {
		this.key = key;
	}

	public String getKey() {
		return key;
	}

	public String getLabelAr() {
		return labelAr;
	}

	public void setLabelAr(String labelAr) {
		this.labelAr = labelAr;
	}

	public String getLabelEn() {
		return labelEn;
	}

	public void setLabelEn(String labelEn) {
		this.labelEn = labelEn;
	}

	public String getUpdatedBy() {
		return updatedBy;
	}

	public void setUpdatedBy(String updatedBy) {
		this.updatedBy = updatedBy;
	}

	public Date getUpdatedDate() {
		return updatedDate;
	}

	public void setUpdatedDate(Date updatedDate) {
		this.updatedDate = updatedDate;
	}
}
