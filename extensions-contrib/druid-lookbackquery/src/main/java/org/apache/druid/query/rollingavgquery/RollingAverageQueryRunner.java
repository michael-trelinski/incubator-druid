/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.druid.query.rollingavgquery;

import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.query.QueryContexts;
import org.joda.time.Interval;
import org.joda.time.Period;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.query.rollingavgquery.averagers.AveragerFactory;
import org.apache.druid.data.input.MapBasedRow;
import org.apache.druid.data.input.Row;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.granularity.PeriodGranularity;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QuerySegmentWalker;
import org.apache.druid.query.QueryToolChestWarehouse;
import org.apache.druid.query.Result;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.UnionDataSource;
import org.apache.druid.query.groupby.GroupByQuery;
import org.apache.druid.query.spec.MultipleIntervalSegmentSpec;
import org.apache.druid.query.timeseries.TimeseriesQuery;
import org.apache.druid.query.timeseries.TimeseriesResultValue;
import org.apache.druid.server.QueryStats;
import org.apache.druid.server.RequestLogLine;
import org.apache.druid.server.log.RequestLogger;

import javax.annotation.Nullable;

/**
 * The QueryRunner for RollingAverage query
 */
public class RollingAverageQueryRunner implements QueryRunner<Row>
{

  public static final String QUERY_FAIL_TIME = "queryFailTime";
  public static final String QUERY_TOTAL_BYTES_GATHERED = "queryTotalBytesGathered";

  private final QuerySegmentWalker walker;
  private final RequestLogger requestLogger;

  public RollingAverageQueryRunner(
      QueryToolChestWarehouse warehouse,
      @Nullable QuerySegmentWalker walker,
      RequestLogger requestLogger
  )
  {
    this.walker = walker;
    this.requestLogger = requestLogger;
  }

  @Override
  public Sequence<Row> run(QueryPlus<Row> query, Map<String, Object> responseContext)
  {

    RollingAverageQuery raq = (RollingAverageQuery) query.getQuery();
    List<Interval> intervals;
    final Period period;

    // Get the largest bucket from the list of averagers
    Optional<Integer> opt =
        raq.getAveragerSpecs().stream().map(AveragerFactory::getNumBuckets).max(Integer::compare);
    int buckets = opt.orElse(0);

    //Extend the interval beginning by specified bucket - 1
    if (raq.getGranularity() instanceof PeriodGranularity) {
      period = ((PeriodGranularity) raq.getGranularity()).getPeriod();
      int offset = buckets <= 0 ? 0 : (1 - buckets);
      intervals = raq.getIntervals()
                     .stream()
                     .map(i -> new Interval(i.getStart().withPeriodAdded(period, offset), i.getEnd()))
                     .collect(Collectors.toList());
    } else {
      throw new ISE("Only PeriodGranulaity is supported for rollingAverage queries");
    }

    Sequence<Row> resultsSeq;
    DataSource dataSource = raq.getDataSource();
    if (raq.getDimensions() != null && !raq.getDimensions().isEmpty() &&
        (dataSource instanceof TableDataSource || dataSource instanceof UnionDataSource ||
         dataSource instanceof QueryDataSource)) {
      // build groupBy query from rollingAverage query
      GroupByQuery.Builder builder = GroupByQuery.builder()
                                                 .setDataSource(dataSource)
                                                 .setInterval(intervals)
                                                 .setDimFilter(raq.getFilter())
                                                 .setGranularity(raq.getGranularity())
                                                 .setDimensions(raq.getDimensions())
                                                 .setAggregatorSpecs(raq.getAggregatorSpecs())
                                                 .setPostAggregatorSpecs(raq.getPostAggregatorSpecs())
                                                 .setContext(raq.getContext());
      GroupByQuery gbq = builder.build();

      HashMap<String, Object> gbqResponse = new HashMap<>();
      gbqResponse.put(QUERY_FAIL_TIME, System.currentTimeMillis() + QueryContexts.getTimeout(gbq));
      gbqResponse.put(QUERY_TOTAL_BYTES_GATHERED, new AtomicLong());

      Sequence<Row> results = gbq.getRunner(walker).run(QueryPlus.wrap(gbq), gbqResponse);
      try {
        // use localhost for remote address
        requestLogger.log(new RequestLogLine(
            DateTimes.nowUtc(),
            "127.0.0.1",
            gbq,
            new QueryStats(
                ImmutableMap.<String, Object>of(
                    "query/time", 0,
                    "query/bytes", 0,
                    "success", true
                ))
        ));
      }
      catch (Exception e) {
        throw Throwables.propagate(e);
      }

      resultsSeq = results;
    } else {
      // no dimensions, so optimize this as a TimeSeries
      TimeseriesQuery tsq = new TimeseriesQuery(
          dataSource,
          new MultipleIntervalSegmentSpec(intervals),
          false,
          null,
          raq.getFilter(),
          raq.getGranularity(),
          raq.getAggregatorSpecs(),
          raq.getPostAggregatorSpecs(),
          0,
          raq.getContext()
      );
      HashMap<String, Object> tsqResponse = new HashMap<>();
      tsqResponse.put(QUERY_FAIL_TIME, System.currentTimeMillis() + QueryContexts.getTimeout(tsq));
      tsqResponse.put(QUERY_TOTAL_BYTES_GATHERED, new AtomicLong());

      Sequence<Result<TimeseriesResultValue>> results = tsq.getRunner(walker).run(QueryPlus.wrap(tsq), tsqResponse);
      try {
        // use localhost for remote address
        requestLogger.log(new RequestLogLine(
            DateTimes.nowUtc(),
            "127.0.0.1",
            tsq,
            new QueryStats(
                ImmutableMap.<String, Object>of(
                    "query/time", 0,
                    "query/bytes", 0,
                    "success", true
                ))
        ));
      }
      catch (Exception e) {
        throw Throwables.propagate(e);
      }

      resultsSeq = Sequences.map(results, new TimeseriesResultToRow());
    }

    // Process into day buckets
    Sequence<RowBucket> bucketedRollingAvgResults =
        Sequences.simple(new RowBucketIterable(resultsSeq, intervals, period));

    // Apply the windows analysis functions
    Sequence<Row> rollingAvgResults = Sequences.simple(
        new RollingAverageIterable(
            bucketedRollingAvgResults,
            raq.getDimensions(),
            raq.getAveragerSpecs(),
            raq.getPostAggregatorSpecs(),
            raq.getAggregatorSpecs()
        ));

    // Apply any postAveragers
    Sequence<Row> rollingAvgResultsWithPostAveragers =
        Sequences.map(rollingAvgResults, new PostAveragerCalculator(raq));

    // remove rows outside the reporting widnow
    List<Interval> reportingIntervals = raq.getIntervals();
    rollingAvgResults =
        Sequences.filter(
            rollingAvgResultsWithPostAveragers,
            row -> reportingIntervals.stream().anyMatch(i -> i.contains(row.getTimestamp()))
        );

    // Apply any having, sorting, and limits
    rollingAvgResults = ((RollingAverageQuery) raq).applyLimit(rollingAvgResults);

    return rollingAvgResults;

  }

  static class TimeseriesResultToRow implements Function<Result<TimeseriesResultValue>, Row>
  {
    public Row apply(Result<TimeseriesResultValue> lookbackResult)
    {
      Map<String, Object> event = lookbackResult.getValue().getBaseObject();
      MapBasedRow row = new MapBasedRow(lookbackResult.getTimestamp(), event);
      return row;
    }
  }
}
