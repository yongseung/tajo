/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tajo.engine.planner.physical;

import org.apache.hadoop.fs.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tajo.TajoTestingCluster;
import tajo.TaskAttemptContext;
import tajo.catalog.*;
import tajo.catalog.proto.CatalogProtos.DataType;
import tajo.catalog.proto.CatalogProtos.StoreType;
import tajo.conf.TajoConf;
import tajo.datum.Datum;
import tajo.datum.DatumFactory;
import tajo.engine.parser.QueryAnalyzer;
import tajo.engine.planner.LogicalPlanner;
import tajo.engine.planner.PhysicalPlanner;
import tajo.engine.planner.PhysicalPlannerImpl;
import tajo.engine.planner.PlanningContext;
import tajo.engine.planner.logical.JoinNode;
import tajo.engine.planner.logical.LogicalNode;
import tajo.storage.*;
import tajo.util.CommonTestingUtil;
import tajo.util.TUtil;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestBNLJoinExec {
  private TajoConf conf;
  private final String TEST_PATH = "target/test-data/TestNLJoinExec";
  private TajoTestingCluster util;
  private CatalogService catalog;
  private QueryAnalyzer analyzer;
  private LogicalPlanner planner;
  private StorageManager sm;

  private static int OUTER_TUPLE_NUM = 1000;
  private static int INNER_TUPLE_NUM = 1000;

  @Before
  public void setUp() throws Exception {
    util = new TajoTestingCluster();
    catalog = util.startCatalogCluster().getCatalog();
    Path workingPath = CommonTestingUtil.buildTestDir(TEST_PATH);
    conf = util.getConfiguration();
    sm = StorageManager.get(conf, workingPath);

    Schema schema = new Schema();
    schema.addColumn("managerId", DataType.INT);
    schema.addColumn("empId", DataType.INT);
    schema.addColumn("memId", DataType.INT);
    schema.addColumn("deptName", DataType.STRING);

    TableMeta employeeMeta = TCatUtil.newTableMeta(schema, StoreType.CSV);
    sm.initTableBase(employeeMeta, "employee");
    Appender appender = sm.getAppender(employeeMeta, "employee", "employee");
    Tuple tuple = new VTuple(employeeMeta.getSchema().getColumnNum());
    for (int i = 0; i < OUTER_TUPLE_NUM; i++) {
      tuple.put(new Datum[] { DatumFactory.createInt(i),
          DatumFactory.createInt(i), DatumFactory.createInt(10 + i),
          DatumFactory.createString("dept_" + i) });
      appender.addTuple(tuple);
    }
    appender.flush();
    appender.close();
    TableDesc employee = TCatUtil.newTableDesc("employee", employeeMeta,
        sm.getTablePath("people"));
    catalog.addTable(employee);

    Schema peopleSchema = new Schema();
    peopleSchema.addColumn("empId", DataType.INT);
    peopleSchema.addColumn("fk_memId", DataType.INT);
    peopleSchema.addColumn("name", DataType.STRING);
    peopleSchema.addColumn("age", DataType.INT);
    TableMeta peopleMeta = TCatUtil.newTableMeta(peopleSchema, StoreType.CSV);
    sm.initTableBase(peopleMeta, "people");
    appender = sm.getAppender(peopleMeta, "people", "people");
    tuple = new VTuple(peopleMeta.getSchema().getColumnNum());
    for (int i = 1; i < INNER_TUPLE_NUM; i += 2) {
      tuple.put(new Datum[] { DatumFactory.createInt(i),
          DatumFactory.createInt(10 + i),
          DatumFactory.createString("name_" + i),
          DatumFactory.createInt(30 + i) });
      appender.addTuple(tuple);
    }
    appender.flush();
    appender.close();

    TableDesc people = TCatUtil.newTableDesc("people", peopleMeta,
        sm.getTablePath("people"));
    catalog.addTable(people);
    analyzer = new QueryAnalyzer(catalog);
    planner = new LogicalPlanner(catalog);
  }

  @After
  public void tearDown() throws Exception {
    util.shutdownCatalogCluster();
  }

  String[] QUERIES = {
      "select managerId, e.empId, deptName, e.memId from employee as e, people",
      "select managerId, e.empId, deptName, e.memId from employee as e " +
          "inner join people as p on e.empId = p.empId and e.memId = p.fk_memId" };

  @Test
  public final void testCrossJoin() throws IOException {
    Fragment[] empFrags = sm.split("employee");
    Fragment[] peopleFrags = sm.split("people");

    Fragment[] merged = TUtil.concat(empFrags, peopleFrags);

    Path workDir = CommonTestingUtil.buildTestDir("target/test-data/testCrossJoin");
    TaskAttemptContext ctx = new TaskAttemptContext(conf,
        TUtil.newQueryUnitAttemptId(), merged, workDir);
    PlanningContext context = analyzer.parse(QUERIES[0]);
    LogicalNode plan = planner.createPlan(context);
    //LogicalOptimizer.optimize(ctx, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    /*ProjectionExec proj = (ProjectionExec) exec;
    NLJoinExec nlJoin = (NLJoinExec) proj.getChild();
    SeqScanExec scanOuter = (SeqScanExec) nlJoin.getOuter();
    SeqScanExec scanInner = (SeqScanExec) nlJoin.getInner();

    BNLJoinExec bnl = new BNLJoinExec(ctx, nlJoin.getJoinNode(), scanOuter,
        scanInner);
    proj.setsubOp(bnl);*/

    int i = 0;
    exec.init();
    while (exec.next() != null) {
      i++;
    }
    exec.close();
    assertEquals(OUTER_TUPLE_NUM * INNER_TUPLE_NUM / 2, i); // expected 10 * 5
  }

  @Test
  public final void testInnerJoin() throws IOException {
    Fragment[] empFrags = sm.split("employee");
    Fragment[] peopleFrags = sm.split("people");

    Fragment[] merged = TUtil.concat(empFrags, peopleFrags);

    Path workDir = CommonTestingUtil.buildTestDir("target/test-data/testEvalExpr");
    TaskAttemptContext ctx =
        new TaskAttemptContext(conf, TUtil.newQueryUnitAttemptId(),
            merged, workDir);
    PlanningContext context = analyzer.parse(QUERIES[1]);
    LogicalNode plan = planner.createPlan(context);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    SeqScanExec scanOuter = null;
    SeqScanExec scanInner = null;

    ProjectionExec proj = (ProjectionExec) exec;
    JoinNode joinNode = null;
    if (proj.getChild() instanceof MergeJoinExec) {
      MergeJoinExec join = (MergeJoinExec) proj.getChild();
      ExternalSortExec sortOut = (ExternalSortExec) join.getOuterChild();
      ExternalSortExec sortIn = (ExternalSortExec) join.getInnerChild();
      scanOuter = (SeqScanExec) sortOut.getChild();
      scanInner = (SeqScanExec) sortIn.getChild();
      joinNode = join.getJoinNode();
    } else if (proj.getChild() instanceof HashJoinExec) {
      HashJoinExec join = (HashJoinExec) proj.getChild();
      scanOuter = (SeqScanExec) join.getOuterChild();
      scanInner = (SeqScanExec) join.getInnerChild();
      joinNode = join.getPlan();
    }

    BNLJoinExec bnl = new BNLJoinExec(ctx, joinNode, scanOuter,
        scanInner);
    proj.setChild(bnl);

    Tuple tuple;
    int i = 1;
    int count = 0;
    exec.init();
    while ((tuple = exec.next()) != null) {
      count++;
      assertTrue(i == tuple.getInt(0).asInt());
      assertTrue(i == tuple.getInt(1).asInt());
      assertTrue(("dept_" + i).equals(tuple.getString(2).asChars()));
      assertTrue(10 + i == tuple.getInt(3).asInt());
      i += 2;
    }
    exec.close();
    assertEquals(INNER_TUPLE_NUM / 2, count);
  }
}
