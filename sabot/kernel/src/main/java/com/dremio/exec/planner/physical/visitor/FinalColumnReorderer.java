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
package com.dremio.exec.planner.physical.visitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;

import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.ProjectPrel;
import com.dremio.exec.planner.physical.ScreenPrel;
import com.dremio.exec.planner.physical.UnionPrel;
import com.dremio.exec.planner.physical.WriterPrel;

public class FinalColumnReorderer extends BasePrelVisitor<Prel, Void, RuntimeException>{

  private static FinalColumnReorderer INSTANCE = new FinalColumnReorderer();

  public static Prel addFinalColumnOrdering(Prel prel) {
    return prel.accept(INSTANCE, null);
  }

  @Override
  public Prel visitScreen(ScreenPrel prel, Void value) throws RuntimeException {
    Prel newChild = ((Prel) prel.getInput()).accept(this, value);
    return prel.copy(prel.getTraitSet(), Collections.singletonList( (RelNode) addTrivialOrderedProjectPrel(newChild, true)));
  }

  private Prel addTrivialOrderedProjectPrel(Prel prel) {
    RelDataType t = prel.getRowType();

    RexBuilder b = prel.getCluster().getRexBuilder();
    List<RexNode> projections = new ArrayList<>();
    int projectCount = t.getFieldList().size();

    // no point in reordering if we only have one column
    if (projectCount < 2) {
      return prel;
    }

    for (int i = 0; i < projectCount; i++) {
      projections.add(b.makeInputRef(prel, i));
    }
    return ProjectPrel.create(prel.getCluster(), prel.getTraitSet(), prel, projections, prel.getRowType());
  }

  private Prel addTrivialOrderedProjectPrel(Prel prel, boolean checkNecessity) {
    if(checkNecessity && !prel.needsFinalColumnReordering()) {
      return prel;
    } else {
      return addTrivialOrderedProjectPrel(prel);
    }
  }

  @Override
  public Prel visitWriter(WriterPrel prel, Void value) throws RuntimeException {
    Prel newChild = ((Prel) prel.getInput()).accept(this, null);
    return prel.copy(prel.getTraitSet(), Collections.singletonList( (RelNode) addTrivialOrderedProjectPrel(newChild, true)));
  }

  @Override
  public Prel visitPrel(Prel prel, Void value) throws RuntimeException {

    List<RelNode> children = new ArrayList<>();
    boolean changed = false;
    for (Prel p : prel) {
      Prel newP = p.accept(this, null);
      if (newP != p) {
        changed = true;
      }
      children.add(newP);
    }
    if (changed) {
      return (Prel) prel.copy(prel.getTraitSet(), children);
    } else {
      return prel;
    }
  }

  @Override
  public Prel visitUnion(UnionPrel prel, Void value) {
    return addColumnOrderingBelowUnion(prel);
  }

  private Prel addColumnOrderingBelowUnion(Prel prel) {
    List<RelNode> children = new ArrayList<>();
    for (Prel p : prel) {
      Prel child = p.accept(this, null);

      boolean needProjectBelowUnion = !(p instanceof ProjectPrel);
      if(needProjectBelowUnion) {
        child = addTrivialOrderedProjectPrel(child, false);
      }

      children.add(child);
    }

    return (Prel) prel.copy(prel.getTraitSet(), children);
  }
}
