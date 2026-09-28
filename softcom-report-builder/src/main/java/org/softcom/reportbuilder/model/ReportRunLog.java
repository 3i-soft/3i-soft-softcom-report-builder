package org.softcom.reportbuilder.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/** One report execution (preview, run page or export): who, what, how long, how many rows. */
@Entity
@Table(name = "rb_report_run_log")
public class ReportRunLog implements Serializable {

	private static final long serialVersionUID = 1L;

	public enum Kind {
		PREVIEW, RUN, EXPORT
	}

	@Id
	@SequenceGenerator(name = "rbReportRunLogSeq", sequenceName = "rb_report_run_log_seq", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "rbReportRunLogSeq")
	private Long id;

	@Column(name = "definition_id")
	private Long definitionId;

	@Column(name = "datasource_key", length = 150)
	private String dataSourceKey;

	@Column(name = "kind", length = 20)
	private String kind;

	@Column(name = "run_by", length = 100)
	private String runBy;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "started_at")
	private Date startedAt;

	@Column(name = "duration_ms")
	private long durationMs;

	@Column(name = "row_count")
	private int rowCount;

	@Column(name = "success")
	private boolean success;

	@Column(name = "error_message", length = 1000)
	private String errorMessage;

	public Long getId() {
		return id;
	}

	public Long getDefinitionId() {
		return definitionId;
	}

	public void setDefinitionId(Long definitionId) {
		this.definitionId = definitionId;
	}

	public String getDataSourceKey() {
		return dataSourceKey;
	}

	public void setDataSourceKey(String dataSourceKey) {
		this.dataSourceKey = dataSourceKey;
	}

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public String getRunBy() {
		return runBy;
	}

	public void setRunBy(String runBy) {
		this.runBy = runBy;
	}

	public Date getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Date startedAt) {
		this.startedAt = startedAt;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(long durationMs) {
		this.durationMs = durationMs;
	}

	public int getRowCount() {
		return rowCount;
	}

	public void setRowCount(int rowCount) {
		this.rowCount = rowCount;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage == null || errorMessage.length() <= 1000 ? errorMessage
				: errorMessage.substring(0, 1000);
	}
}
