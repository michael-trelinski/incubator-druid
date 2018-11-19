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

import java.util.List;
import java.util.Map;

import com.google.common.base.Function;
import org.apache.druid.data.input.MapBasedRow;
import org.apache.druid.data.input.Row;
import org.apache.druid.query.aggregation.PostAggregator;

/**
 * Function that can be applied to a Sequence to calculate PostAverager results
 */
public class PostAveragerCalculator implements Function<Row, Row>
{

  private final List<PostAggregator> postAveragers;

  public PostAveragerCalculator(RollingAverageQuery raq)
  {
    this.postAveragers = raq.getPostAveragerSpecs();
  }

  /* (non-Javadoc)
   * @see com.google.common.base.Function#apply(java.lang.Object)
   */
  @Override
  public Row apply(Row input)
  {
    MapBasedRow row = (MapBasedRow) input;
    Map<String, Object> event = row.getEvent();

    for (PostAggregator postAverager : postAveragers) {
      boolean allColsPresent = postAverager.getDependentFields().stream().allMatch(c -> event.get(c) != null);
      event.put(postAverager.getName(), allColsPresent ? postAverager.compute(event) : null);
    }

    return input;
  }

}
