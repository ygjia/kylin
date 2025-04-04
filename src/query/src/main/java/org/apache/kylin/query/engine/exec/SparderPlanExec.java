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

package org.apache.kylin.query.engine.exec;

import java.util.List;

import org.apache.calcite.DataContext;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.kylin.common.KapConfig;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.QueryContext;
import org.apache.kylin.common.QueryTrace;
import org.apache.kylin.guava30.shaded.common.collect.ImmutableList;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.metadata.cube.cuboid.NLayoutCandidate;
import org.apache.kylin.metadata.cube.model.IndexEntity;
import org.apache.kylin.metadata.project.NProjectManager;
import org.apache.kylin.query.engine.exec.sparder.QueryEngine;
import org.apache.kylin.query.engine.meta.MutableDataContext;
import org.apache.kylin.query.engine.meta.SimpleDataContext;
import org.apache.kylin.query.relnode.ContextUtil;
import org.apache.kylin.query.relnode.OlapContext;
import org.apache.kylin.query.relnode.OlapRel;
import org.apache.kylin.query.runtime.SparkEngine;
import org.apache.kylin.query.util.QueryContextCutter;
import org.apache.kylin.query.util.QueryHelper;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

/**
 * implement and execute a physical plan with Sparder
 */
@Slf4j
public class SparderPlanExec implements QueryPlanExec {

    private KylinConfig kylinConfig;
    
    public SparderPlanExec(KylinConfig kylinConfig) {
        this.kylinConfig = kylinConfig;
    }

    @Override
    public List<List<String>> execute(RelNode rel, MutableDataContext dataContext) {
        return ImmutableList.copyOf(executeToIterable(rel, dataContext).getRows());
    }

    @Override
    public ExecuteResult executeToIterable(RelNode rel, MutableDataContext dataContext) {

        ContextUtil.dumpCalcitePlan("Calcite PLAN BEFORE SELECT REALIZATION:", rel, log);
        QueryContext.current().record("end_plan");
        QueryContext.current().getQueryTagInfo().setWithoutSyntaxError(true);

        // select realizations
        QueryContext.currentTrace().startSpan(QueryTrace.MODEL_MATCHING);
        QueryContextCutter.selectRealization(QueryContext.current().getProject(), rel,
                QueryContext.current().isForModeling());

        QueryContext.current().getQueryPlan().setCalcitePlan(RelOptUtil.toString(rel));
        ContextUtil.dumpCalcitePlan("Calcite PLAN AFTER SELECT REALIZATION:", rel, log);

        // used for printing query plan when diagnosing query problem
        if (NProjectManager.getProjectConfig(QueryContext.current().getProject()).isPrintQueryPlanEnabled()) {
            log.info(QueryContext.current().getQueryPlan().getCalcitePlan());
        }

        val contexts = ContextUtil.listContexts();
        if (KapConfig.wrap(kylinConfig).runConstantQueryLocally()
                && checkNotAsyncQueryAndCalciteEngineCapable(rel)
                && contexts.stream().allMatch(context -> hasEmptyRealization(context))) {
            return new CalcitePlanExec().executeToIterable(rel, dataContext);
        }

        // skip if no segment is selected
        // check contentQuery and runConstantQueryLocally for UT cases to make sure SparderEnv.getDF is not null
        // refactor IT tests and remove this runConstantQueryLocally checking ???
        if (!(dataContext instanceof SimpleDataContext) || !((SimpleDataContext) dataContext).isContentQuery()
                || KapConfig.wrap(((SimpleDataContext) dataContext).getKylinConfig()).runConstantQueryLocally()) {
            for (OlapContext context : contexts) {
                if (context.getOlapSchema() != null && context.getStorageContext().isDataSkipped()) {
                    QueryContext.current().setOutOfSegmentRange(true);
                    if (!QueryContext.current().getQueryTagInfo().isAsyncQuery() && !context.isHasAgg()) {
                        QueryContext.fillEmptyResultSetMetrics();
                        return new ExecuteResult(Lists.newArrayList(), 0);
                    }
                }
            }
        }

        // rewrite
        rewrite(rel);

        // query detect
        if (QueryContext.current().getQueryTagInfo().isQueryDetect()) {
            return new ExecuteResult(Lists.newArrayList(), 0);
        }

        // all OlapContext are constant query, or can be answered by metadata
        if (checkNotAsyncQueryAndCalciteEngineCapable(rel)
                && isAllOlapContextCanBeAnsweredLocally(contexts)) {
            return new CalcitePlanExec().executeToIterable(rel, dataContext);
        }

        // submit rel and dataContext to query engine
        return internalCompute(new SparkEngine(), dataContext, rel.getInput(0));
    }

    private boolean checkNotAsyncQueryAndCalciteEngineCapable(RelNode rel) {
        return !QueryContext.current().getQueryTagInfo().isAsyncQuery() && QueryHelper.isCalciteEngineCapable(rel);
    }

    private boolean isAllOlapContextCanBeAnsweredLocally(List<OlapContext> olapContexts) {
        boolean runConstantQueryLocally = KapConfig.wrap(kylinConfig).runConstantQueryLocally();
        boolean runQueryLocallyWhenRouteToMetadata = kylinConfig.runQueryLocallyWhenRouteToMetadata();
        for (OlapContext olapContext : olapContexts) {
            if (!isOlapContextCanBeAnsweredLocally(olapContext, runConstantQueryLocally,
                    runQueryLocallyWhenRouteToMetadata)) {
                return false;
            }
        }
        return true;
    }

    private boolean isOlapContextCanBeAnsweredLocally(OlapContext olapContext, boolean runConstantQueryLocally,
            boolean runQueryLocallyWhenRouteToMetadata) {
        if (!runConstantQueryLocally && !runQueryLocallyWhenRouteToMetadata) {
            return false;
        }
        RelNode topNode = olapContext.getTopNode();
        if (runConstantQueryLocally && QueryHelper.isConstantQueryAndCalciteEngineCapable(topNode)) {
            return true;
        }
        if (runConstantQueryLocally && hasEmptyRealization(olapContext)) {
            return true;
        }
        return runQueryLocallyWhenRouteToMetadata && olapContext.checkOlapContextAnsweredByMetadata();
    }

    private boolean hasEmptyRealization(OlapContext context) {
        return context.getRealization() == null && context.isConstantQueryWithAggregations();
    }

    protected ExecuteResult internalCompute(QueryEngine queryEngine, DataContext dataContext, RelNode rel) {
        return queryEngine.computeToIterable(dataContext, rel);
    }

    /**
     * rewrite relNodes
     */
    private void rewrite(RelNode rel) {
        // rewrite query if necessary
        OlapRel.RewriteImpl rewriteImpl = new OlapRel.RewriteImpl();
        rewriteImpl.visitChild(rel, rel.getInput(0));
        ContextUtil.dumpCalcitePlan("EXECUTION PLAN AFTER REWRITE", rel, log);

        QueryContext.current().getQueryTagInfo().setSparderUsed(true);

        boolean exactlyMatch = ContextUtil.listContextsHavingScan().stream().noneMatch(this::isAggImperfectMatch);

        QueryContext.current().getMetrics().setExactlyMatch(exactlyMatch);

        ContextUtil.setOlapRel((OlapRel) rel.getInput(0));
        ContextUtil.setRowType(rel.getRowType());

        QueryContext.current().record("end_rewrite");
    }

    private boolean isAggImperfectMatch(OlapContext ctx) {
        NLayoutCandidate candidate = ctx.getStorageContext().getBatchCandidate();
        long layoutId = candidate.getLayoutEntity().getId();
        return layoutId < 0 || IndexEntity.isAggIndex(layoutId) && !ctx.isExactlyAggregate()
                || IndexEntity.isTableIndex(layoutId) && ctx.isHasAgg();
    }
}
