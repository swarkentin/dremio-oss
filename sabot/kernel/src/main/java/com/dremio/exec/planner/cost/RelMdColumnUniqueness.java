/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
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
package com.dremio.exec.planner.cost;

import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.metadata.BuiltInMetadata;
import org.apache.calcite.rel.metadata.MetadataDef;
import org.apache.calcite.rel.metadata.MetadataHandler;
import org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableBitSet;

import com.dremio.exec.planner.logical.ProjectableSqlAggFunction;

/**
 * Override {@link RelMdColumnUniqueness}
 */
public class RelMdColumnUniqueness implements MetadataHandler<BuiltInMetadata.ColumnUniqueness> {
  private static final RelMdColumnUniqueness INSTANCE = new RelMdColumnUniqueness();

  public static final RelMetadataProvider SOURCE =
      ReflectiveRelMetadataProvider.reflectiveSource(
          BuiltInMethod.COLUMN_UNIQUENESS.method, INSTANCE);


  @Override
  public MetadataDef<BuiltInMetadata.ColumnUniqueness> getDef() {
    return BuiltInMetadata.ColumnUniqueness.DEF;
  }

  public Boolean areColumnsUnique(LogicalAggregate rel, RelMetadataQuery mq, ImmutableBitSet columns, boolean ignoreNulls) {
    for (AggregateCall call : rel.getAggCallList()) {
      SqlAggFunction aggFunction = call.getAggregation();
      if (aggFunction instanceof ProjectableSqlAggFunction && !((ProjectableSqlAggFunction) aggFunction).isUnique()) {
        return false;
      }
    }

    ImmutableBitSet groupKey = ImmutableBitSet.range(rel.getGroupCount());
    return columns.contains(groupKey);
  }
}
