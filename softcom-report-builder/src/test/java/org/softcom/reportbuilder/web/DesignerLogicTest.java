package org.softcom.reportbuilder.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
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

	@Test
	public void linksAreSavedAndRemovingOneTakesItsFieldsOut() {
		ReportDesignerBean d = new ReportDesignerBean();
		d.setDataSourceKey("lines");
		d.addLink();
		d.addLink();
		assertEquals(Arrays.asList("l1", "l2"), Arrays.asList(d.getLinks().get(0).getAlias(), d.getLinks().get(1).getAlias()));
		org.softcom.reportbuilder.spec.LinkSpec l1 = d.getLinks().get(0);
		org.softcom.reportbuilder.spec.LinkSpec l2 = d.getLinks().get(1);
		l1.setDataSource("stock");
		l1.getOn().get(0).setLeft("item.id");
		l1.getOn().get(0).setRight("item_id");
		d.addLinkPair(l1);
		l1.getOn().get(1).setLeft("warehouse.id");
		l1.getOn().get(1).setRight("warehouse_id");
		l2.setDataSource("items");
		l2.getOn().get(0).setLeft("l1:item_id"); // the second link uses the first one's column
		l2.getOn().get(0).setRight("id");
		d.getColumns().add(new ColumnSpec("quantity", Aggregate.NONE));
		d.getColumns().add(new ColumnSpec("l1:available", Aggregate.NONE));
		d.getColumns().add(new ColumnSpec("l2:name", Aggregate.NONE));
		d.getSorts().add(new SortSpec(2, false)); // sort by l2:name
		GroupEditor g = new GroupEditor();
		RuleEditor own = new RuleEditor("r1");
		own.setField("quantity");
		own.setOperator(Operator.GT);
		RuleEditor linked = new RuleEditor("r2");
		linked.setField("l1:available");
		linked.setOperator(Operator.GT);
		g.getRules().add(own);
		g.getRules().add(linked);
		d.getGroups().add(g);

		ReportSpec spec = d.buildSpec();
		assertEquals(2, spec.getLinks().size());
		assertEquals("stock", spec.getLinks().get(0).getDataSource());
		assertEquals(2, spec.getLinks().get(0).getOn().size());
		assertEquals("warehouse_id", spec.getLinks().get(0).getOn().get(1).getRight());
		assertNotSame("the spec gets copies, not the editors", l1, spec.getLinks().get(0));

		d.removeLink(l1);
		assertEquals(1, d.getLinks().size());
		assertEquals("its column is gone", Arrays.asList("quantity", "l2:name"),
				Arrays.asList(d.getColumns().get(0).getField(), d.getColumns().get(1).getField()));
		assertEquals("the sort follows its column", 1, d.getSorts().get(0).getColumn());
		assertEquals("its condition is gone", 1, g.getRules().size());
		assertEquals("quantity", g.getRules().get(0).getField());
		assertNull("the later link's pair that used it is cleared", l2.getOn().get(0).getLeft());
		assertEquals("id", l2.getOn().get(0).getRight());
		d.addLink();
		assertEquals("the free alias is used again", "l1", d.getLinks().get(1).getAlias());
	}

	@Test
	public void changingALinkedTableResetsItsPairsAndFields() {
		ReportDesignerBean d = new ReportDesignerBean();
		d.setDataSourceKey("lines");
		d.addLink();
		org.softcom.reportbuilder.spec.LinkSpec l1 = d.getLinks().get(0);
		l1.setDataSource("stock");
		l1.getOn().get(0).setLeft("item.id");
		l1.getOn().get(0).setRight("item_id");
		d.addLinkPair(l1);
		d.getColumns().add(new ColumnSpec("l1:available", Aggregate.SUM));
		l1.setDataSource("prices");
		d.onLinkDataSourceChange(l1);
		assertEquals(1, l1.getOn().size());
		assertNull(l1.getOn().get(0).getLeft());
		assertTrue(d.getColumns().isEmpty());
		for (int i = 0; i < org.softcom.reportbuilder.engine.SpecValidator.MAX_LINK_PAIRS + 2; i++)
			d.addLinkPair(l1);
		assertEquals(org.softcom.reportbuilder.engine.SpecValidator.MAX_LINK_PAIRS, l1.getOn().size());
		d.removeLinkPair(l1, l1.getOn().get(0));
		assertEquals(org.softcom.reportbuilder.engine.SpecValidator.MAX_LINK_PAIRS - 1, l1.getOn().size());
		for (int i = 0; i < 5; i++)
			d.addLink();
		assertEquals(org.softcom.reportbuilder.engine.SpecValidator.MAX_LINKS, d.getLinks().size());
		assertFalse(d.isCanAddLink());
	}
}
