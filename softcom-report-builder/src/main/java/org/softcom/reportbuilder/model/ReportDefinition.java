package org.softcom.reportbuilder.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.Version;

/**
 * A saved user report. {@link #getDefinition()} holds the {@code ReportSpec} as
 * JSON (a PostgreSQL {@code text} column). The table lives in the host
 * application's own database; see META-INF/softcom-report-builder/sql.
 */
@Entity
@Table(name = "rb_report_definition")
@NamedQueries({
		@NamedQuery(name = ReportDefinition.FIND_OWNED_OR_SHARED, query = "SELECT d FROM ReportDefinition d WHERE d.owner = :owner OR d.shared = true ORDER BY d.name"),
		@NamedQuery(name = ReportDefinition.FIND_ALL, query = "SELECT d FROM ReportDefinition d ORDER BY d.name") })
public class ReportDefinition implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String FIND_OWNED_OR_SHARED = "rb.ReportDefinition.findOwnedOrShared";
	public static final String FIND_ALL = "rb.ReportDefinition.findAll";

	@Id
	@SequenceGenerator(name = "rbReportDefinitionSeq", sequenceName = "rb_report_definition_seq", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "rbReportDefinitionSeq")
	private Long id;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "description", length = 1000)
	private String description;

	@Column(name = "datasource_key", nullable = false, length = 150)
	private String dataSourceKey;

	@Column(name = "definition", nullable = false)
	private String definition;

	@Column(name = "owner", nullable = false, length = 100)
	private String owner;

	@Column(name = "shared", nullable = false)
	private boolean shared;

	/** Comma separated role names allowed to run a shared report; empty = every report user. */
	@Column(name = "shared_roles", length = 1000)
	private String sharedRoles;

	@Version
	@Column(name = "version")
	private Integer version;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "created_date")
	private Date createdDate;

	@Column(name = "created_by", length = 100)
	private String createdBy;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "updated_date")
	private Date updatedDate;

	@Column(name = "updated_by", length = 100)
	private String updatedBy;

	public List<String> getSharedRoleList() {
		List<String> list = new ArrayList<>();
		if (sharedRoles != null)
			for (String r : sharedRoles.split(","))
				if (!r.trim().isEmpty())
					list.add(r.trim());
		return list;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getDataSourceKey() {
		return dataSourceKey;
	}

	public void setDataSourceKey(String dataSourceKey) {
		this.dataSourceKey = dataSourceKey;
	}

	public String getDefinition() {
		return definition;
	}

	public void setDefinition(String definition) {
		this.definition = definition;
	}

	public String getOwner() {
		return owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	public boolean isShared() {
		return shared;
	}

	public void setShared(boolean shared) {
		this.shared = shared;
	}

	public String getSharedRoles() {
		return sharedRoles;
	}

	public void setSharedRoles(String sharedRoles) {
		this.sharedRoles = sharedRoles;
	}

	public Integer getVersion() {
		return version;
	}

	/** Only for carrying the version the user loaded back to {@code ReportDefinitionFacade.save} (lost-update check). */
	public void setVersion(Integer version) {
		this.version = version;
	}

	public Date getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(Date createdDate) {
		this.createdDate = createdDate;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public Date getUpdatedDate() {
		return updatedDate;
	}

	public void setUpdatedDate(Date updatedDate) {
		this.updatedDate = updatedDate;
	}

	public String getUpdatedBy() {
		return updatedBy;
	}

	public void setUpdatedBy(String updatedBy) {
		this.updatedBy = updatedBy;
	}
}
