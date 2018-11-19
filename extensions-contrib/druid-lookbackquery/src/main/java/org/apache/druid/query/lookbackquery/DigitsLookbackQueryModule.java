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
package org.apache.druid.query.lookbackquery;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.Module;
import com.google.inject.Binder;
import com.google.inject.multibindings.MapBinder;

import org.apache.druid.guice.DruidBinders;
import org.apache.druid.guice.LazySingleton;
import org.apache.druid.initialization.DruidModule;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.Query;

import java.util.Arrays;
import java.util.List;

/**
 * DigitsLookbackQueryModule is an extention of Druid for creating a new query type 'LookbackQuery'
 */
public class DigitsLookbackQueryModule implements DruidModule
{

  @Override
  public void configure(Binder binder)
  {
    MapBinder<Class<? extends Query>, QueryToolChest> toolChests = DruidBinders.queryToolChestBinder(binder);

    //Bind the query toolchest to the query class and add the binding to toolchest
    toolChests.addBinding(LookbackQuery.class).to(LookbackQueryToolChest.class);

    //Bind the query toolchest to binder
    binder.bind(LookbackQueryToolChest.class).in(LazySingleton.class);
  }

  @Override
  public List<? extends Module> getJacksonModules()
  {
    return Arrays.<Module>asList(new SimpleModule("DigitsLookbackQueryModule")
                                     .registerSubtypes(new NamedType(LookbackQuery.class, "lookback")));
  }

}
