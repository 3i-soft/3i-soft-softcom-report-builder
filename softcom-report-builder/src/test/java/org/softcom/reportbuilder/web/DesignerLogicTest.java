package org.softcom.reportbuilder.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SortSpec;
import org.softcom.reportbuilder.spi.FieldType;

/** Designer/viewer logic that does not need a container. */
public class DesignerLogicTest {

	@Test
	public void pickListValuesOfAStringFieldSurviveEditing() {
		// a STRING code field with a choice list: the page shows a checkbox menu bound to "selected"
		RuleEditor r = new RuleEditor("r1");
		r.setField("item.itemGeneralClassification");
		r.resetFor(FieldType.STRING, Operator.IN);
		r.setSelected(Arrays.asList("11", "12"));
		FilterNode n = r.toNode();
		assertEquals(Arrays.asList("11", "12"), n.getValues());
		assertEquals(Arrays.asList("11", "12"), r.parameterValues());
		RuleEditor back = RuleEditor.from(n, FieldType.STRING, true);
		assertEquals(Arrays.asList("11", "12"), back.getSelected());
		assertNull(back.getListText());
		// without a choice list the values go to the free-text list
		RuleEditor text = RuleEditor.from(n, FieldType.STRING, false);
		assertEquals("11, 12", text.getListText());
		assertEquals(Arrays.asList("11", "12"), text.toNode().getValues());
	}

	@Test
	public void changingFieldOrOperatorClearsPromptAndValues() {
		RuleEditor r = new RuleEditor("r1");
		r.resetFor(FieldType.DATE, Operator.BETWEEN);
		r.setValue("2026-01-01");
		r.setAskAtRun(true);
		r.setLabel("Period");
		r.setOperator(Operator.IS_NULL);
		r.onOperatorChanged();
		assertFalse("no prompt for an operator without a value", r.isAskAtRun());
		assertNull(r.getValue());
		r.setAskAtRun(true);
		r.resetFor(FieldType.STRING, Operator.CONTAINS);
		assertFalse(r.isAskAtRun());
		assertNull(r.getLabel());
	}

	@Test
	public void sortFollowsItsColumnWhenEmptyRowsAreSkipped() {
		ReportDesignerBean d = new ReportDesignerBean();
		d.setDataSourceKey("x");
		d.getColumns().add(new ColumnSpec("a", Aggregate.NONE));
		d.getColumns().add(new ColumnSpec(null, Aggregate.NONE)); // row without a field
		d.getColumns().add(new ColumnSpec("c", Aggregate.NONE));
		d.getColumns().add(new ColumnSpec("d", Aggregate.SUM));
		d.getSorts().add(new SortSpec(3, true)); // sort by "d" (designer row 3)
		d.getSorts().add(new SortSpec(1, false)); // sort by the empty row: dropped
		ReportSpec spec = d.buildSpec();
		assertEquals(3, spec.getColumns().size());
		assertEquals(1, spec.getSort().size());
		assertEquals(2, spec.getSort().get(0).getColumn());
		assertEquals("d", spec.getColumns().get(spec.getSort().get(0).getColumn()).getField());
		assertTrue(spec.getSort().get(0).isDescending());
	}
}
