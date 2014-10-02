/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.engine.planner.physical;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.tajo.catalog.Column;
import org.apache.tajo.catalog.Schema;
import org.apache.tajo.catalog.SortSpec;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.conf.TajoConf.ConfVars;
import org.apache.tajo.engine.planner.PlannerUtil;
import org.apache.tajo.engine.planner.logical.CreateIndexNode;
import org.apache.tajo.storage.RowStoreUtil;
import org.apache.tajo.storage.Tuple;
import org.apache.tajo.storage.TupleComparator;
import org.apache.tajo.storage.VTuple;
import org.apache.tajo.storage.index.bst.BSTIndex;
import org.apache.tajo.storage.index.bst.BSTIndex.BSTIndexWriter;
import org.apache.tajo.worker.TaskAttemptContext;

import java.io.IOException;

public class StoreIndexExec extends UnaryPhysicalExec {
  private static final Log LOG = LogFactory.getLog(StoreIndexExec.class);
  private BSTIndexWriter indexWriter;
  private final CreateIndexNode logicalPlan;
  private int[] indexKeys = null;
  private Schema keySchema;
  private TupleComparator comparator;

  public StoreIndexExec(final TaskAttemptContext context, final CreateIndexNode logicalPlan,
                        final PhysicalExec child) {
    super(context, logicalPlan.getInSchema(), logicalPlan.getOutSchema(), child);
    this.logicalPlan = logicalPlan;
  }

  @Override
  public void init() throws IOException {
    super.init();

    SortSpec[] sortSpecs = logicalPlan.getSortSpecs();
    indexKeys = new int[sortSpecs.length];
    keySchema = PlannerUtil.sortSpecsToSchema(sortSpecs);

    Column col;
    for (int i = 0 ; i < sortSpecs.length; i++) {
      col = sortSpecs[i].getSortKey();
      indexKeys[i] = inSchema.getColumnId(col.getQualifiedName());
    }

    TajoConf conf = context.getConf();

//    String[] splits = logicalPlan.getIndexName().split("\\.");
//    Path indexPath = new Path(conf.getVar(ConfVars.WAREHOUSE_DIR), splits[0] + "/" + splits[1] + "/" +
//        context.getUniqueKeyFromFragments());
    Path indexPath = logicalPlan.getIndexPath();
    System.out.println("exec: " + indexPath);
    // TODO: Create factory using reflection
    BSTIndex bst = new BSTIndex(conf);
    this.comparator = new TupleComparator(keySchema, sortSpecs);
    this.indexWriter = bst.getIndexWriter(indexPath, BSTIndex.TWO_LEVEL_INDEX, keySchema, comparator);
    this.indexWriter.setLoadNum(100);
    this.indexWriter.open();
  }

  @Override
  public Tuple next() throws IOException {
    Tuple tuple;
    Tuple keyTuple;
    Tuple prevKeyTuple = null;
    long offset;

    while((tuple = child.next()) != null) {
      offset = tuple.getOffset();
      keyTuple = new VTuple(keySchema.size());
      RowStoreUtil.project(tuple, keyTuple, indexKeys);
      if (prevKeyTuple == null || !prevKeyTuple.equals(keyTuple)) {
        indexWriter.write(keyTuple, offset);
        prevKeyTuple = keyTuple;
      }
    }
    return null;
  }

  @Override
  public void close() throws IOException {
    super.close();

    indexWriter.flush();
    IOUtils.cleanup(LOG, indexWriter);

    indexWriter = null;
  }
}
